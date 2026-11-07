/* SPDX-License-Identifier: GPL-3.0-or-later */
/** Изолированная проверка исходников; не запускает игровой мир и базу. */
package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

public final class OlympiadActionPaceTest {
    private static int checks;
    private static void check(boolean value,String name) {checks++;if(!value)throw new AssertionError(name);}

    private static List<Long> replay(boolean fast) {
        var clock=new OlympiadActionPace.DecisionClock();
        List<Long> casts=new ArrayList<>();long castEnds=0;
        for(long now=0;now<=4000;now+=10) {
            boolean pass=now%(fast?200:2000)==0;
            if(pass && clock.claim(now,200) && now>=castEnds) {casts.add(now);castEnds=now+350;}
            
            if(fast && now%2000==0 && clock.claim(now,200) && now>=castEnds) {casts.add(now);castEnds=now+350;}
        }
        return casts;
    }
    public static void main(String[] args)throws Exception {
        var clock=new OlympiadActionPace.DecisionClock();
        check(clock.claim(0,200),"first decision immediate");
        for(int ms=1;ms<200;ms++)check(!clock.claim(ms,200),"no duplicate decision at"+ms);
        check(clock.claim(200,200),"next native pace slot");
        check(!clock.claim(200,200),"same slot not repeated by slow driver");
        check(clock.claim(400,200),"following slot");
        List<Long> old=replay(false),fresh=replay(true);
        check(old.equals(List.of(0L,2000L,4000L)),"old2second arena cadence reproduced");
        check(fresh.equals(List.of(0L,400L,800L,1200L,1600L,2000L,2400L,2800L,3200L,3600L,4000L)),"new decisions can act after legitimate350ms cast");
        for(int i=1;i<fresh.size();i++) {
            long interval=fresh.get(i)-fresh.get(i-1);
            check(interval>=350,"cast duration never skipped");
            check(interval-350<=200,"no extra1second wait after cast");
        }
        check(!OlympiadActionPace.owns(null),"no effect without an arena entry");

        Object lock=OlympiadStartKick.lockFor(4321);
        var shared=new OlympiadActionPace.DecisionClock();
        var start=new CyclicBarrier(20);var winners=new AtomicInteger();var threads=new ArrayList<Thread>();
        for(int i=0;i<20;i++) {
            Thread t=new Thread(()->{
                try{start.await();}catch(Exception e){throw new AssertionError(e);}
                synchronized(lock){if(shared.claim(1000,200))winners.incrementAndGet();}
            });threads.add(t);t.start();
        }
        for(Thread t:threads)t.join();
        check(winners.get()==1,"concurrent start/slow/fast callbacks produce one decision");
        System.out.println("OlympiadActionPace: "+checks+" checks passed; timeline model only, no world/SQL/client started.");
    }
}
