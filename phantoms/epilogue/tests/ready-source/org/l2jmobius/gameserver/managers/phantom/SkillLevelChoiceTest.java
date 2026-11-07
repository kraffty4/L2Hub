/* SPDX-License-Identifier: GPL-3.0-or-later */
/** Изолированная проверка исходников; не запускает игровой мир и базу. */
package org.l2jmobius.gameserver.managers.phantom;

import java.lang.classfile.ClassFile;
import java.lang.classfile.Instruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

public final class SkillLevelChoiceTest {
    private static int checks;
    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }
    private static NavigableSet<Integer> levels(int maximum) {
        var result = new TreeSet<Integer>();
        for (int level = 1; level <= maximum; level++) result.add(level);
        return result;
    }

    static final class Account implements SkillLevelChoice.Access {
        final Map<Integer, String> stored = new HashMap<>();
        final Map<Integer, Integer> nativeLearnedRows = new HashMap<>();
        final Map<Integer, Integer> runtime = new HashMap<>();
        final Map<Integer, Integer> treeMaximum = new HashMap<>();
        int slot, available = 9, refreshes, saves, writes;
        boolean blocked, cooldown, failSave, failApply;

        Account() { learn(121, 6); treeMaximum.put(121, 9); }
        void learn(int id, int level) { nativeLearnedRows.put(id, level); runtime.put(id, level); }
        public String read() { return stored.getOrDefault(slot, ""); }
        public boolean save(String value) {
            saves++;
            stored.put(slot, value);
            if (failSave) { failSave = false; return false; }
            return true;
        }
        public SkillLevelChoice.View view(int id) {
            Integer level = runtime.get(id), maximum = treeMaximum.get(id);
            return level == null || maximum == null ? null : new SkillLevelChoice.View(id, "Battle Roar", level, levels(maximum), levels(Math.min(available, maximum)));
        }
        public boolean blocked() { return blocked; }
        public boolean cooling(int id, int highest) { return cooldown; }
        public void useLevel(int id, int level) {
            if (failApply) { failApply = false; throw new IllegalStateException("simulated failed application"); }
            writes++;
            if (!runtime.containsKey(id)) throw new AssertionError("Created an unknown skill");
            runtime.put(id, level);
        }
        public void refresh() { refreshes++; }
    }

    public static void main(String[] args) throws Exception {
        Account player = new Account();
        check(SkillLevelChoice.select(player, 121, 1, true) == SkillLevelChoice.Result.CHANGED, "select level1");
        check(player.runtime.get(121) == 1, "selected level applies");
        check(player.nativeLearnedRows.get(121) == 6, "native learned level remains intact");
        check(player.read().equals("121:1:6"), "preference retains natural level");
        check(player.refreshes == 1, "skill window/shortcuts refreshed once");

        player.learn(121, 7); 
        check(SkillLevelChoice.reapply(player), "reapply after learning");
        check(player.runtime.get(121) == 1 && player.nativeLearnedRows.get(121) == 7, "auto-learn does not erase choice or native earned skill");
        check(player.read().equals("121:1:7"), "new native level retained for later restoration");

        Account reconnected = new Account();
        reconnected.learn(121, 7);
        reconnected.stored.put(0, player.read());
        check(SkillLevelChoice.reapply(reconnected) && reconnected.runtime.get(121) == 1, "reconnect reapplies saved preference");
        int saves = reconnected.saves, writes = reconnected.writes;
        for (int n = 0; n < 10; n++) check(!SkillLevelChoice.reapply(reconnected), "idempotent refresh" + n);
        check(reconnected.saves == saves && reconnected.writes == writes, "no repeated writes when already selected");

        String mainPreference = reconnected.read();
        reconnected.slot = 1;
        reconnected.learn(121, 3);
        check(!SkillLevelChoice.reapply(reconnected) && reconnected.runtime.get(121) == 3, "no main-to-subclass preference leakage");
        check(SkillLevelChoice.select(reconnected, 121, 2, true) == SkillLevelChoice.Result.CHANGED, "subclass choice");
        check(reconnected.stored.get(0).equals(mainPreference) && reconnected.read().equals("121:2:3"), "subclass stored separately");
        reconnected.slot = 0;
        reconnected.learn(121, 7);
        check(SkillLevelChoice.reapply(reconnected) && reconnected.runtime.get(121) == 1, "return to main class");

        Account other = new Account();
        check(!SkillLevelChoice.reapply(other) && other.runtime.get(121) == 6, "no cross-character leakage");
        check(SkillLevelChoice.select(other, 121, 7, true) == SkillLevelChoice.Result.INVALID_LEVEL, "cannot gain an available but unearned level");
        check(SkillLevelChoice.select(other, 121, 0, true) == SkillLevelChoice.Result.INVALID_LEVEL, "level0 rejected");
        check(SkillLevelChoice.select(other, 121, -1, true) == SkillLevelChoice.Result.INVALID_LEVEL, "negative level rejected");
        check(SkillLevelChoice.select(other, 999, 1, true) == SkillLevelChoice.Result.NO_SKILL, "unknown skill rejected");
        check(SkillLevelChoice.select(other, 121, 1, false) == SkillLevelChoice.Result.DISABLED, "disabled service");
        other.blocked = true;
        check(SkillLevelChoice.select(other, 121, 1, true) == SkillLevelChoice.Result.BLOCKED, "in-combat/Olympiad state rejected");
        other.blocked = false; other.cooldown = true;
        check(SkillLevelChoice.select(other, 121, 1, true) == SkillLevelChoice.Result.COOLDOWN, "old level cooldown cannot be bypassed");
        other.cooldown = false;
        check(other.read().isEmpty() && other.runtime.get(121) == 6, "rejected commands leave account intact");

        check(SkillLevelChoice.select(other, 121, 1, true) == SkillLevelChoice.Result.CHANGED, "lower level");
        other.cooldown = true;
        check(SkillLevelChoice.automatic(other, 121) == SkillLevelChoice.Result.COOLDOWN, "restore cannot bypass lower level reuse");
        other.cooldown = false;
        check(SkillLevelChoice.automatic(other, 121) == SkillLevelChoice.Result.CHANGED, "restore earlier earned level");
        check(other.runtime.get(121) == 6 && other.read().isEmpty(), "restore does not grant future level9");

        Account failure = new Account(); failure.failSave = true;
        check(SkillLevelChoice.select(failure, 121, 1, true) == SkillLevelChoice.Result.SAVE_FAILED, "save failure reported");
        check(failure.runtime.get(121) == 6 && failure.read().isEmpty(), "save failure does not change runtime/selection");
        failure.failApply = true;
        check(SkillLevelChoice.select(failure, 121, 1, true) == SkillLevelChoice.Result.SAVE_FAILED, "application failure reported");
        check(failure.runtime.get(121) == 6 && failure.read().isEmpty(), "application failure rolls back");

        Account special = new Account();
        special.runtime.put(121, 101);
        check(SkillLevelChoice.select(special, 121, 1, true) == SkillLevelChoice.Result.UNSUPPORTED, "enchant version cannot be downgraded");
        special.stored.put(0, "121:1:6");
        check(!SkillLevelChoice.reapply(special) && special.runtime.get(121) == 101, "auto-overlay preserves new enchant");
        check(SkillLevelChoice.automatic(special, 121) == SkillLevelChoice.Result.CHANGED && special.runtime.get(121) == 101, "clearing preference preserves enchant");
        special.stored.put(0, "121:1:6"); special.runtime.remove(121);
        check(SkillLevelChoice.automatic(special, 121) == SkillLevelChoice.Result.CHANGED && !special.runtime.containsKey(121), "missing skill not recreated");

        Account delevelled = new Account();
        check(SkillLevelChoice.select(delevelled, 121, 5, true) == SkillLevelChoice.Result.CHANGED, "select level5");
        delevelled.available = 3; delevelled.learn(121, 3);
        check(!SkillLevelChoice.reapply(delevelled) && delevelled.runtime.get(121) == 3, "delevel cannot retain unavailable chosen5");
        delevelled.available = 6; delevelled.learn(121, 6);
        check(SkillLevelChoice.reapply(delevelled) && delevelled.runtime.get(121) == 5, "choice resumes after level recovery");

        check(SkillLevelChoice.decode("garbage;0:1:1;121:0:6;3:9:1;121:1:6").equals(Map.of(121, new SkillLevelChoice.Choice(1, 6))), "malformed preferences rejected");
        check(SkillLevelChoice.decode("x".repeat(32769)).isEmpty(), "oversized stored preference bounded");

        Account partial = new Account();
        partial.learn(34, 3); partial.treeMaximum.put(34, 3);
        partial.stored.put(0, "34:1:3;121:1:6"); partial.failApply = true;
        check(SkillLevelChoice.reapply(partial), "one unavailable level does not prevent other preferences");
        check(partial.runtime.get(34) == 3 && partial.runtime.get(121) == 1, "remaining choice applied after failed first one");
        check(partial.nativeLearnedRows.get(34) == 3 && partial.nativeLearnedRows.get(121) == 6, "failed/retried overlay never overwrites native learned rows");

        
        byte[] raw;
        try (var stream = SkillLevelChoice.NativeAccess.class.getResourceAsStream("SkillLevelChoice$NativeAccess.class")) { raw = stream.readAllBytes(); }
        var model = ClassFile.of().parse(raw);
        var use = model.methods().stream().filter(m -> m.methodName().equalsString("useLevel")).findFirst().orElseThrow();
        var instructions = use.code().orElseThrow().elementStream().filter(e -> e instanceof Instruction).toList();
        boolean found = false;
        for (int i = 1; i < instructions.size(); i++) {
            if (instructions.get(i) instanceof InvokeInstruction call && call.name().equalsString("addSkill")) {
                check(instructions.get(i-1) instanceof ConstantInstruction value && Integer.valueOf(0).equals(value.constantValue()), "native learned-row persistence flag is false");
                found = true;
            }
        }
        check(found, "actual native adapter inspected");
        System.out.println("SkillLevelChoice: " + checks + " checks passed; no world/SQL/client started.");
    }
}
