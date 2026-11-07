/* SPDX-License-Identifier: GPL-3.0-or-later */
/** Изолированная проверка исходников; не запускает игровой мир и базу. */
package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

public final class OlympiadStartKickTest {
    private static int checks;
    private static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }
    private static boolean valid(boolean[] f, int[] n) {
        return OlympiadStartKick.eligible(f[0],f[1],f[2],f[3],f[4],f[5],f[6],f[7],n[0],n[1],n[2],n[3],n[4],n[5],n[6]);
    }
    public static void main(String[] args) throws Exception {
        boolean[] flags = {true,true,true,true,false,false,false,false};
        int[] values = {4,4,4,1,2,123,123};
        check(valid(flags, values), "valid started opponents");
        for (int i=0; i<flags.length; i++) {
            boolean[] changed=flags.clone();changed[i]=!changed[i];
            check(!valid(changed, values), "closed start/dead/teleport gate " + i);
        }
        for (int i : new int[]{0,1,2,3,4,5,6}) {
            int[] changed=values.clone();
            changed[i]=switch(i){case 0 -> -1;case 1,2 -> 8;case 3,4 -> 0;default -> 999;};
            check(!valid(flags, changed), "wrong match/side/instance " + i);
        }
        int[] sameSide=values.clone();sameSide[4]=sameSide[3];
        check(!valid(flags,sameSide), "same team not an opponent");
        int[] wrongSide=values.clone();wrongSide[3]=3;
        check(!valid(flags,wrongSide), "invalid observer-like side rejected");
        check(OlympiadStartKick.lockFor(17)==OlympiadStartKick.lockFor(17), "regular driver and first kick share a lock");
        check(OlympiadStartKick.lockFor(17)!=OlympiadStartKick.lockFor(18), "players do not share a global combat lock");

        Object match = new Object();
        List<Runnable> pending = new ArrayList<>();
        AtomicInteger actions = new AtomicInteger();
        check(OlympiadStartKick.queue(match, actions::incrementAndGet, pending::add), "one immediate task queued");
        check(!OlympiadStartKick.queue(match, actions::incrementAndGet, pending::add), "duplicate start not queued");
        check(pending.size()==1 && actions.get()==0, "no action before task dispatch");
        pending.removeFirst().run();
        check(actions.get()==1 && pending.isEmpty(), "one decision, no recurring scheduler");
        OlympiadStartKick.clear(match);

        Object cancelled=new Object();
        check(OlympiadStartKick.queue(cancelled, actions::incrementAndGet, pending::add), "pending task");
        OlympiadStartKick.clear(cancelled);
        pending.removeFirst().run();
        check(actions.get()==1, "cleanup cancels not-yet-dispatched action");

        Object parallel = new Object();
        AtomicInteger queued = new AtomicInteger();
        CyclicBarrier start = new CyclicBarrier(16);
        var threads = new ArrayList<Thread>();
        for(int i=0;i<16;i++) {
            Thread thread=new Thread(()->{
                try {start.await();}
                catch(Exception error){throw new AssertionError(error);}
                OlympiadStartKick.queue(parallel, ()->{}, ignored->queued.incrementAndGet());
            });
            threads.add(thread);thread.start();
        }
        for(Thread thread:threads)thread.join();
        check(queued.get()==1, "concurrent notifications schedule one task");
        OlympiadStartKick.clear(parallel);
        Object rejected = new Object(); boolean failed = false;
        try { OlympiadStartKick.queue(rejected, ()->{}, ignored->{ throw new IllegalStateException("scheduler closed"); }); }
        catch (IllegalStateException expected) { failed = true; }
        check(failed, "scheduler rejection observable to adapter");
        check(OlympiadStartKick.queue(rejected, ()->{}, ignored->{}), "rejected task does not permanently consume match token");
        OlympiadStartKick.clear(rejected);
        System.out.println("OlympiadStartKick: " + checks + " checks passed; no world/SQL/client started.");
    }
}
