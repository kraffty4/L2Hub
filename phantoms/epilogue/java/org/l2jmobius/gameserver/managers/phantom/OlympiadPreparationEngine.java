package org.l2jmobius.gameserver.managers.phantom;

import java.util.*;

/** Clock-driven preparation. The adapter owns all real NPC, mana, reagent and cooldown checks. */
public final class OlympiadPreparationEngine {
  public enum Kind {BUFF_SELF,BUFF_PET,TOGGLE,SUMMON,CUBIC,CHARGE,SOUL}
  public enum Request {ACCEPTED,DONE,WAIT,FAILED}
  public record Action(String key,int skillId,Kind kind,int extra,long earliestMillis,int castMillis){}
  public interface Context {
    boolean beforeBattle();
    boolean available();
    boolean casting();
    int npcRemaining();
    boolean npcReady();
    boolean npcCovered(int index);
    Request npc(int index);
    List<Action> actions();
    boolean covered(Action action);
    boolean usable(Action action);
    Request request(Action action);
    void abortOwnCast();
  }
  private final long started,deadline;
  private final List<Integer> npcOrder;
  private final Set<Integer> npcTried=new HashSet<>();
  private final Set<String> finished=new HashSet<>();
  private final Map<String,Integer> attempts=new HashMap<>();
  private final Map<String,Long> retryAfter=new HashMap<>();
  private boolean npcDone,done;
  private Action pending;
  private long pendingUntil,npcDeadline;
  private int npcUsed,skillsConfirmed,failed,castTimeouts;

  public OlympiadPreparationEngine(long now,List<Integer> npcOrder,long budgetMillis){
    if(budgetMillis<=0)throw new IllegalArgumentException("Preparation budget must be positive");
    this.started=now;this.deadline=now+budgetMillis;this.npcOrder=List.copyOf(npcOrder);
  }
  public void seal(){done=true;}
  public boolean done(){return done;}
  public int npcUsed(){return npcUsed;}
  public int skillsConfirmed(){return skillsConfirmed;}
  public int failed(){return failed;}
  public int castTimeouts(){return castTimeouts;}
  public long started(){return started;}
  private void settleFailure(Action action,long now){
    failed++;retryAfter.put(action.key(),now+2000);
    if(attempts.getOrDefault(action.key(),0)>=2)finished.add(action.key());
  }
  public void tick(Context c,long now){
    if(done)return;
    if(!c.beforeBattle()||now>=deadline){done=true;return;}
    if(!c.available())return;
    if(pending!=null){
      if(c.covered(pending)){
        finished.add(pending.key());skillsConfirmed++;pending=null;
      }else if(c.casting()){
        if(now>=pendingUntil){c.abortOwnCast();castTimeouts++;settleFailure(pending,now);pending=null;}
        return;
      }else if(now<pendingUntil){return;}
      else{settleFailure(pending,now);pending=null;}
    }
    // Never replace a cast that was not initiated by this state machine.
    if(c.casting())return;
    if(!npcDone){
      if(npcDeadline==0)npcDeadline=started+Math.min(24000,Math.max(6000,(Math.min(c.npcRemaining(),npcOrder.size())+2L)*2000));
      if(c.npcRemaining()<=0||now>=npcDeadline){npcDone=true;}
      else if(!c.npcReady()){
        if(now-started>=5000)npcDone=true;
        else return;
      }else{
        for(int index:npcOrder){
          if(npcTried.contains(index))continue;
          if(c.npcCovered(index)){npcTried.add(index);continue;}
          int before=c.npcRemaining();Request result=c.npc(index);
          if(result==Request.WAIT)return;
          npcTried.add(index);
          if(result==Request.DONE&&c.npcRemaining()<before)npcUsed+=before-c.npcRemaining();else failed++;
          return;
        }
        npcDone=true;
      }
    }
    boolean future=false;
    for(Action action:c.actions()){
      if(finished.contains(action.key()))continue;
      if(now-started<action.earliestMillis()){future=true;continue;}
      if(c.covered(action)){finished.add(action.key());continue;}
      if(attempts.getOrDefault(action.key(),0)>=2){finished.add(action.key());continue;}
      if(now<retryAfter.getOrDefault(action.key(),0L)){future=true;continue;}
      if(!c.usable(action)){finished.add(action.key());failed++;continue;}
      Request result=c.request(action);
      if(result==Request.WAIT){future=true;continue;}
      attempts.merge(action.key(),1,Integer::sum);
      if(result==Request.DONE){finished.add(action.key());skillsConfirmed++;}
      else if(result==Request.ACCEPTED){pending=action;pendingUntil=now+Math.max(6000,action.castMillis()+2500);}
      else settleFailure(action,now);
      return;
    }
    if(!future)done=true;
  }
}
