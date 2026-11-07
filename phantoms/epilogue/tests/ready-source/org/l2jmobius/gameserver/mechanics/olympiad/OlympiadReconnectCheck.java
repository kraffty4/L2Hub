/* SPDX-License-Identifier: GPL-3.0-or-later */
/** Изолированная проверка исходников; не запускает игровой мир и базу. */
package org.l2jmobius.gameserver.mechanics.olympiad;

import java.lang.reflect.Field;
import java.util.Map;


public final class OlympiadReconnectCheck {
    static int checks;
    static void check(boolean value,String name) {checks++;if(!value)throw new AssertionError(name);}
    public static void main(String[] args)throws Exception {
        check(EpilogueOlympiadReconnect.mayRebind(true,true,true,false,false,-1,2),"same connected actor retains native cleanup");
        check(EpilogueOlympiadReconnect.mayRebind(false,true,true,false,false,2,2),"relogged actor on same physical arena selected");
        for(int mask=0;mask<16;mask++) {
            boolean online=(mask&1)!=0,spawned=(mask&2)!=0,observer=(mask&4)!=0,other=(mask&8)!=0;
            check(EpilogueOlympiadReconnect.mayRebind(false,online,spawned,observer,other,2,2)==(online&&spawned&&!observer&&!other),"rebind safety gates"+mask);
        }
        check(!EpilogueOlympiadReconnect.mayRebind(false,true,true,false,false,-1,2),"already-returned town actor not pulled back");
        check(!EpilogueOlympiadReconnect.mayRebind(false,true,true,false,false,3,2),"unrelated arena not touched");
        check(!EpilogueOlympiadReconnect.mayRebind(true,true,true,false,true,2,2),"new match protected even for same Player object");
        check(!EpilogueOlympiadReconnect.mayRebind(true,true,true,true,false,2,2),"GM observer protected");
        Field field=EpilogueOlympiadReconnect.class.getDeclaredField("PENDING");field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Integer,EpilogueOlympiadReconnect.Ticket> pending=(Map<Integer,EpilogueOlympiadReconnect.Ticket>)field.get(null);
        EpilogueOlympiadReconnect.remember(51,2,82047,147622,-3464,100);
        check(pending.get(51).equals(new EpilogueOlympiadReconnect.Ticket(2,82047,147622,-3464,100)),"native origin saved for offline return");
        EpilogueOlympiadReconnect.remember(51,4,1,2,3,200);
        EpilogueOlympiadReconnect.remember(51,2,82047,147622,-3464,100);
        check(pending.get(51).match()==200&&pending.get(51).arena()==4,"late old cleanup cannot overwrite newer return");
        EpilogueOlympiadReconnect.remember(52,2,4,5,6,100);
        check(pending.get(51).arena()==4&&pending.get(52).x()==4,"per-character tickets independent");
        EpilogueOlympiadReconnect.remember(0,2,1,2,3,100);
        EpilogueOlympiadReconnect.remember(53,-1,1,2,3,100);
        check(!pending.containsKey(0)&&!pending.containsKey(53),"invalid owners rejected");
        pending.clear();
        System.out.println("OlympiadReconnectCheck: "+checks+" checks passed; no World/SQL/player mutation");
    }
}
