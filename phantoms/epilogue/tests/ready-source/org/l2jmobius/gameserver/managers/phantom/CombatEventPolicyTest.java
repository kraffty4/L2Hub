/* SPDX-License-Identifier: GPL-3.0-or-later */
/** Изолированная проверка исходников; не запускает игровой мир и базу. */
package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

public final class CombatEventPolicyTest {
    private static int checks;
    private static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }
    public static void main(String[] args) throws Exception {
        check(!CombatEventPolicy.sanctioned(false, false, false, false, false, false), "ordinary world attack remains a world event");
        for (int mask = 1; mask < 64; mask++) {
            check(CombatEventPolicy.sanctioned((mask&1)!=0, (mask&2)!=0, (mask&4)!=0, (mask&8)!=0, (mask&16)!=0, (mask&32)!=0), "sanctioned combat context " + mask);
        }
        var map = new ConcurrentHashMap<String, Long>();
        String key = CombatEventPolicy.key(12, 25, "ATTACKED");
        check(key.equals(CombatEventPolicy.key(12, 26, "ATTACKED")), "many attackers share one complaint budget");
        check(!key.equals(CombatEventPolicy.key(13, 25, "ATTACKED")), "different hunters have separate budgets");
        check(!CombatEventPolicy.key(12, 25, "PARTY_INVITE").equals(CombatEventPolicy.key(12, 26, "PARTY_INVITE")), "unrelated pairwise events remain pairwise");
        check(CombatEventPolicy.reserve(map, key, 1000, 180000), "first reaction allowed");
        check(!CombatEventPolicy.reserve(map, key, 1001, 180000), "repeat attack rejected");
        check(!CombatEventPolicy.reserve(map, key, 180999, 180000), "cooldown includes last millisecond");
        check(CombatEventPolicy.reserve(map, key, 181000, 180000), "cooldown releases exactly at deadline");

        map.clear();
        AtomicInteger winners = new AtomicInteger();
        CyclicBarrier start = new CyclicBarrier(24);
        var threads = new ArrayList<Thread>();
        for (int n = 0; n < 24; n++) {
            final int attacker = n;
            Thread thread = new Thread(() -> {
                try { start.await(); }
                catch (Exception error) { throw new AssertionError(error); }
                if (CombatEventPolicy.reserve(map, CombatEventPolicy.key(12, attacker, "ATTACKED"), 1000, 180000)) winners.incrementAndGet();
            });
            threads.add(thread); thread.start();
        }
        for (Thread thread : threads) thread.join();
        check(winners.get() == 1, "concurrent damage has one reply, not24");
        check(map.size() == 1, "burst does not allocate a cooldown per attacker");
        System.out.println("CombatEventPolicy: " + checks + " checks passed; no world/SQL/client started.");
    }
}
