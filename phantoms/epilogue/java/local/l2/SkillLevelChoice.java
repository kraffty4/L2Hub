package local.l2;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.data.xml.SkillTreeData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.network.serverpackets.ShortcutInit;

/** A persistent preference overlays ordinary class skills; native learned skill rows stay intact. */
public final class SkillLevelChoice {
    static final String PREFIX = "L2Local.SkillLevels.";
    private static final Logger LOG = Logger.getLogger(SkillLevelChoice.class.getName());
    private static final Map<Integer, Object> LOCKS = new ConcurrentHashMap<>();
    private static final int PAGE_SIZE = 8;

    enum Result { CHANGED, UNCHANGED, DISABLED, BLOCKED, NO_SKILL, INVALID_LEVEL, UNSUPPORTED, COOLDOWN, SAVE_FAILED }
    record Choice(int selected, int earned) {}
    record View(int id, String name, int known, NavigableSet<Integer> ordinary, NavigableSet<Integer> available) {
        boolean regular() { return ordinary.contains(known); }
    }
    interface Access {
        String read();
        boolean save(String value);
        View view(int id);
        boolean blocked();
        boolean cooling(int id, int highest);
        void useLevel(int id, int level);
        void refresh();
    }

    private SkillLevelChoice() {}

    static Map<Integer, Choice> decode(String value) {
        Map<Integer, Choice> result = new TreeMap<>();
        if (value == null || value.length() > 32768) return result;
        for (String token : value.split(";")) {
            if (result.size() >= 256) break;
            String[] fields = token.split(":", -1);
            if (fields.length != 3) continue;
            try {
                int id = Integer.parseInt(fields[0]);
                int selected = Integer.parseInt(fields[1]);
                int earned = Integer.parseInt(fields[2]);
                if (id > 0 && selected > 0 && earned >= selected) result.put(id, new Choice(selected, earned));
            } catch (NumberFormatException ignored) { }
        }
        return result;
    }

    static String encode(Map<Integer, Choice> choices) {
        StringBuilder result = new StringBuilder();
        new TreeMap<>(choices).forEach((id, choice) -> {
            if (!result.isEmpty()) result.append(';');
            result.append(id).append(':').append(choice.selected()).append(':').append(choice.earned());
        });
        return result.toString();
    }

    private static boolean commit(Access access, String before, Map<Integer, Choice> choices) {
        try {
            if (access.save(encode(choices))) return true;
        } catch (RuntimeException error) {
            LOG.log(Level.WARNING, "Could not save skill level preference", error);
        }
        try { access.save(before); }
        catch (RuntimeException error) { LOG.log(Level.WARNING, "Could not restore skill level preference", error); }
        return false;
    }

    static Result select(Access access, int id, int level, boolean enabled) {
        if (!enabled) return Result.DISABLED;
        if (access.blocked()) return Result.BLOCKED;
        View view = access.view(id);
        if (view == null) return Result.NO_SKILL;
        if (!view.regular()) return Result.UNSUPPORTED;
        String before = access.read();
        Map<Integer, Choice> choices = decode(before);
        Choice previous = choices.get(id);
        int earned = Math.min(view.ordinary().last(), Math.max(view.known(), previous == null ? 0 : previous.earned()));
        if (level <= 0 || level > earned || !view.available().contains(level)) return Result.INVALID_LEVEL;
        if (access.cooling(id, earned)) return Result.COOLDOWN;
        if (previous != null && previous.selected() == level && view.known() == level) return Result.UNCHANGED;
        choices.put(id, new Choice(level, earned));
        if (!commit(access, before, choices)) return Result.SAVE_FAILED;
        try {
            if (view.known() != level) access.useLevel(id, level);
            access.refresh();
            return Result.CHANGED;
        } catch (RuntimeException error) {
            try { access.save(before); access.useLevel(id, view.known()); }
            catch (RuntimeException rollback) { LOG.log(Level.WARNING, "Skill selection rollback failed", rollback); }
            LOG.log(Level.WARNING, "Could not apply skill level preference", error);
            return Result.SAVE_FAILED;
        }
    }

    static Result automatic(Access access, int id) {
        if (access.blocked()) return Result.BLOCKED;
        String before = access.read();
        Map<Integer, Choice> choices = decode(before);
        Choice previous = choices.get(id);
        if (previous == null) return Result.UNCHANGED;
        View view = access.view(id);
        int target = 0;
        if (view != null && view.regular()) {
            int earned = Math.max(view.known(), previous.earned());
            Integer allowed = view.available().floor(earned);
            target = allowed == null ? view.known() : Math.max(view.known(), allowed);
            if (access.cooling(id, earned)) return Result.COOLDOWN;
        }
        // Missing, enchanted, item and transformation skills are never created or replaced here.
        choices.remove(id);
        if (!commit(access, before, choices)) return Result.SAVE_FAILED;
        try {
            if (view != null && target > 0 && target != view.known()) access.useLevel(id, target);
            access.refresh();
            return Result.CHANGED;
        } catch (RuntimeException error) {
            try { access.save(before); if (view != null && target > 0) access.useLevel(id, view.known()); }
            catch (RuntimeException rollback) { LOG.log(Level.WARNING, "Skill restoration rollback failed", rollback); }
            LOG.log(Level.WARNING, "Could not restore learned skill level", error);
            return Result.SAVE_FAILED;
        }
    }

    static boolean reapply(Access access) {
        String before = access.read();
        Map<Integer, Choice> choices = decode(before);
        Map<Integer, Integer> changes = new TreeMap<>();
        for (var entry : choices.entrySet()) {
            int id = entry.getKey();
            Choice choice = entry.getValue();
            View view = access.view(id);
            if (view == null || !view.regular()) continue;
            int earned = Math.min(view.ordinary().last(), Math.max(choice.earned(), view.known()));
            Integer target = view.available().floor(Math.min(choice.selected(), earned));
            entry.setValue(new Choice(choice.selected(), earned));
            if (target != null && target != view.known()) changes.put(id, target);
        }
        if (!encode(choices).equals(before) && !commit(access, before, choices)) return false;
        boolean changed = false;
        for (var entry : changes.entrySet()) {
            try { access.useLevel(entry.getKey(), entry.getValue()); changed = true; }
            catch (RuntimeException error) { LOG.log(Level.WARNING, "Could not reapply selected skill " + entry.getKey(), error); }
        }
        return changed;
    }

    private static boolean enabled() {
        return new ConfigReader("config/Custom/SkillLevelSelection.ini").getBoolean("SkillLevelSelectionEnabled", false);
    }

    /** Called after native learning has converged; never cap inside its add/learn loop. */
    public static void afterAutoLearn(Player player) {
        if (player == null || player.isTransformed() || !player.hasVariables() || !enabled()) return;
        try {
            String key = PREFIX + player.getClassIndex();
            if (player.getVariables().getString(key, "").isBlank()) return;
            synchronized (LOCKS.computeIfAbsent(player.getObjectId(), ignored -> new Object())) {
                reapply(new NativeAccess(player));
            }
        } catch (RuntimeException error) {
            LOG.log(Level.WARNING, "Could not reapply selected skill levels", error);
        }
    }

    public static void entered(Player player) {
        if (player == null || player.isTransformed() || player.getClient() == null || !enabled()) return;
        try {
            player.getVariables(); // Real login only; do not load variables for every world phantom.
            synchronized (LOCKS.computeIfAbsent(player.getObjectId(), ignored -> new Object())) {
                NativeAccess access = new NativeAccess(player);
                if (reapply(access)) access.refresh();
            }
        } catch (RuntimeException error) {
            LOG.log(Level.WARNING, "Could not restore selected skill levels on login", error);
        }
    }

    static final class NativeAccess implements Access {
        final Player player;
        final int classIndex;
        final int classId;
        final String key;
        final Map<Integer, NavigableSet<Integer>> ordinary = new TreeMap<>();
        final Map<Integer, NavigableSet<Integer>> available = new TreeMap<>();

        NativeAccess(Player player) {
            this.player = player;
            classIndex = player.getClassIndex();
            classId = player.getPlayerClass().getId();
            key = PREFIX + classIndex;
            for (var learned : SkillTreeData.getInstance().getCompleteClassSkillTree(player.getPlayerClass()).values()) {
                ordinary.computeIfAbsent(learned.getSkillId(), ignored -> new TreeSet<>()).add(learned.getSkillLevel());
                if (learned.getGetLevel() <= player.getLevel()) {
                    available.computeIfAbsent(learned.getSkillId(), ignored -> new TreeSet<>()).add(learned.getSkillLevel());
                }
            }
        }

        public String read() { return player.getVariables().getString(key, ""); }
        public boolean save(String value) {
            var variables = player.getVariables();
            if (value.isEmpty()) variables.remove(key); else variables.set(key, value);
            return !variables.hasChanges() || variables.saveNow();
        }
        public View view(int id) {
            Skill skill = player.getKnownSkill(id);
            if (skill == null || skill.isGMSkill() || skill.isHeroSkill() || !ordinary.containsKey(id)) return null;
            return new View(id, skill.getName(), skill.getLevel(), ordinary.get(id), available.getOrDefault(id, Collections.emptyNavigableSet()));
        }
        public boolean blocked() {
            return player.getClassIndex() != classIndex || player.getPlayerClass().getId() != classId || player.isChangingClass() || player.isDead() || player.isInCombat()
                || player.isCastingNow() || player.isInOlympiadMode() || player.isOnEvent() || player.isInDuel()
                || player.isInStoreMode() || player.isTeleporting() || player.isTransformed();
        }
        public boolean cooling(int id, int highest) {
            for (int level : ordinary.getOrDefault(id, Collections.emptyNavigableSet()).headSet(highest, true)) {
                Skill skill = SkillData.getInstance().getSkill(id, level);
                if (skill != null && player.isSkillDisabled(skill)) return true;
            }
            return false;
        }
        public void useLevel(int id, int level) {
            if (player.getClassIndex() != classIndex || player.getPlayerClass().getId() != classId || player.isTransformed()) throw new IllegalStateException("Character class changed");
            Skill skill = SkillData.getInstance().getSkill(id, level);
            if (skill == null || skill.getId() != id || skill.getLevel() != level || player.getKnownSkill(id) == null) throw new IllegalArgumentException("Missing learned skill level");
            Skill previous = player.getKnownSkill(id);
            if (previous.isToggle() && player.isAffectedBySkill(id)) player.stopSkillEffects(SkillFinishType.REMOVED, id);
            // Persist only the preference. Keep native highest-earned skill rows/enchant data intact.
            player.addSkill(skill, false);
            player.updateShortcuts(id, level);
            // Lowering a passive may reduce maxima; restoring it must never grant a free heal.
            if (player.getCurrentHp() > player.getMaxHp()) player.setCurrentHp(player.getMaxHp());
            if (player.getCurrentMp() > player.getMaxMp()) player.setCurrentMp(player.getMaxMp());
            if (player.getCurrentCp() > player.getMaxCp()) player.setCurrentCp(player.getMaxCp());
        }
        public void refresh() {
            player.sendSkillList();
            player.sendPacket(new ShortcutInit(player));
            player.broadcastUserInfo();
        }
    }

    private static String message(Result result) {
        return switch (result) {
            case CHANGED -> "Выбор сохранён.";
            case UNCHANGED -> "Этот вариант уже выбран.";
            case DISABLED -> "Выбор уровня навыков выключен.";
            case BLOCKED -> "Менять уровень можно вне боя, каста, событий, трансформации и Олимпиады.";
            case NO_SKILL -> "Такого изученного классового навыка нет.";
            case INVALID_LEVEL -> "Этот уровень недоступен или ещё не был изучен.";
            case UNSUPPORTED -> "Заточенные и временные версии навыков здесь не меняются.";
            case COOLDOWN -> "Сначала дождись отката этого навыка. Смена уровня не сбрасывает откаты.";
            case SAVE_FAILED -> "Не удалось сохранить выбор. Попробуй позже.";
        };
    }

    public static String board(Player player, String[] args) {
        NativeAccess access = new NativeAccess(player);
        boolean active = enabled();
        String note = "";
        int page = 0;
        Integer selected = null;
        try {
            if (args.length > 1) {
                String operation = args[1];
                if (operation.equals("page") && args.length == 3) page = Math.max(0, Integer.parseInt(args[2]));
                else if (operation.equals("view") && args.length == 3) selected = Integer.valueOf(args[2]);
                else if (operation.equals("set") && args.length == 4) {
                    int id = Integer.parseInt(args[2]); int level = Integer.parseInt(args[3]);
                    synchronized (LOCKS.computeIfAbsent(player.getObjectId(), ignored -> new Object())) {
                        note = message(select(access, id, level, active));
                    }
                    selected = id;
                } else if (operation.equals("auto") && args.length == 3) {
                    int id = Integer.parseInt(args[2]);
                    synchronized (LOCKS.computeIfAbsent(player.getObjectId(), ignored -> new Object())) {
                        note = message(automatic(access, id));
                    }
                    selected = id;
                } else note = "Неверная команда.";
            }
        } catch (NumberFormatException error) { note = "Неверный номер навыка или уровня."; }
        Map<Integer, Choice> choices = decode(access.read());
        StringBuilder html = new StringBuilder("<br><font color=LEVEL>Уровни навыков</font><br><br>");
        if (!note.isEmpty()) html.append("<font color=LEVEL>").append(PaidColors.esc(note)).append("</font><br><br>");
        html.append("Выбор сохраняется после входа и автоизучения.<br>У основной профессии и каждого саба свой выбор.<br>");
        html.append("Можно вернуть ранее изученный доступный уровень.<br>Откаты не сбрасываются. Заточенные навыки не меняются.<br><br>");
        if (!active) html.append("<font color=LEVEL>Новый выбор выключен. Сохранённый можно отменить.</font><br><br>");
        if (selected != null) {
            View view = access.view(selected);
            Choice choice = choices.get(selected);
            if (view == null) {
                html.append("Изученный навык не найден.<br>");
                if (choice != null) html.append(PaidColors.button("Убрать сохранённый выбор", "_bbshubskilllevels auto " + selected, 260));
            } else {
                html.append("<font color=LEVEL>").append(PaidColors.esc(view.name())).append("</font><br>Сейчас: ").append(view.known()).append(".<br>");
                int earned = Math.max(view.known(), choice == null ? 0 : choice.earned());
                if (view.regular() && active) {
                    List<String> levels = new ArrayList<>();
                    for (int level : view.available().headSet(earned, true)) {
                        Skill candidate = SkillData.getInstance().getSkill(view.id(), level);
                        if (candidate != null && candidate.getId() == view.id() && candidate.getLevel() == level) levels.add(Integer.toString(level));
                    }
                    if (!levels.isEmpty()) html.append("<br><combobox var=\"chosenSkillLevel\" list=\"").append(String.join(";", levels)).append("\" width=90><br>")
                        .append(PaidColors.button("Сохранить уровень", "_bbshubskilllevels set " + view.id() + " $chosenSkillLevel", 220));
                } else if (!view.regular()) html.append("Заточенная или временная версия не меняется.<br>");
                if (choice != null) html.append("<br>Сохранённый предел: ").append(choice.selected()).append(".<br>")
                    .append(PaidColors.button("Вернуть автоуровень", "_bbshubskilllevels auto " + view.id(), 220));
            }
            return html.append("<br>").append(PaidColors.button("К списку навыков", "_bbshubskilllevels", 220)).toString();
        }
        List<View> rows = new ArrayList<>();
        for (Skill skill : player.getSkills().values()) {
            View view = access.view(skill.getId());
            if (view != null && (choices.containsKey(view.id()) || view.regular() && view.known() > 1)) rows.add(view);
        }
        rows.sort(Comparator.comparing(View::name, String.CASE_INSENSITIVE_ORDER).thenComparingInt(View::id));
        int lastPage = Math.max(0, (rows.size() - 1) / PAGE_SIZE);
        page = Math.min(page, lastPage);
        for (View view : rows.subList(page * PAGE_SIZE, Math.min(rows.size(), (page + 1) * PAGE_SIZE))) {
            Choice choice = choices.get(view.id());
            html.append("<font color=LEVEL>").append(PaidColors.esc(view.name())).append("</font> — ур. ").append(view.known());
            if (choice != null) html.append(" (выбрано ").append(choice.selected()).append(')');
            html.append("<br>").append(PaidColors.button("Выбрать уровень", "_bbshubskilllevels view " + view.id(), 200)).append("<br>");
        }
        if (rows.isEmpty()) html.append("Нет обычных классовых навыков для понижения.<br>");
        html.append("Страница ").append(page + 1).append('/').append(lastPage + 1).append("<br>");
        if (page > 0) html.append(PaidColors.button("Предыдущая", "_bbshubskilllevels page " + (page - 1), 130));
        if (page < lastPage) html.append(PaidColors.button("Следующая", "_bbshubskilllevels page " + (page + 1), 130));
        return html.toString();
    }
}
