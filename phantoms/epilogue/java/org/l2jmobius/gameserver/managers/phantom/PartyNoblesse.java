package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.mechanics.effects.EffectFlag;
import org.l2jmobius.gameserver.mechanics.skill.BuffInfo;
import org.l2jmobius.gameserver.mechanics.skill.Skill;

/** Only companions in a human owner's actual party inherit status. Native skills/casts/reagents remain in force. */
public final class PartyNoblesse
{
    static final int BLESSING = 1323;
    private record Link(int owner, boolean support, long seen) { }
    private record Observation(boolean dead, boolean blessed, long urgentUntil, long seen) { }
    private record Claim(int caster, long started, long until) { }
    private static final Map<Integer, Link> LINKS = new ConcurrentHashMap<>();
    private static final Map<Integer, Observation> OBSERVED = new ConcurrentHashMap<>();
    private static final Map<Integer, Claim> CLAIMS = new ConcurrentHashMap<>();
    private static final AtomicLong CLEAN_AT = new AtomicLong();

    private PartyNoblesse() { }

    static boolean protectedByNoblesse(Player player)
    {
        if (player.isAffected(EffectFlag.NOBLESS_BLESSING)) return true;
        BuffInfo info = player.getEffectList().getBuffInfoBySkillId(BLESSING);
        return (info != null) && info.isInUse();
    }

    /** Called only after the native companion manager has accepted the real party membership. */
    static void synchronize(Player owner, Player bot, PhantomTemplate template, long now)
    {
        // Личный помощник наследует право на автоматический1323 только от текущего дворянина.
        if ((bot != null) && ((owner == null) || (template == null)
            || (!template.isHunter() && !template.isReaper() && (!owner.isNoble() || !PhantomPersonalNoblesse.allows(bot, BLESSING)))))
        {
            LINKS.remove(bot.getObjectId());
            return;
        }
        if ((owner == null) || (bot == null) || (owner == bot) || (!owner.isNoble() && !bot.isNoble())
            || bot.isInOlympiadMode() || bot.isOnEvent() || bot.isInDuel()
            || !bot.isInParty() || (bot.getParty() != owner.getParty()) || !bot.getParty().containsPlayer(owner)
            || !PhantomManager.getInstance().isPhantom(bot) || PhantomManager.getInstance().isPhantom(owner)) return;
        LINKS.put(bot.getObjectId(), new Link(owner.getObjectId(), (template != null) && PhantomSupport.isSupport(template), now));
        boolean granted = owner.isNoble() && !bot.isNoble();
        if (granted || (bot.isNoble() && (bot.getKnownSkill(BLESSING) == null)))
        {
            bot.setNoble(true); // Native noble tree, including1323; no hero/GM flags are copied.
            if (granted) bot.store(false); // The native noble flag is persisted once, never removed on leaving a party.
        }
        observe(bot.getParty().getMembers(), now);
        cleanup(now);
    }

    private static void cleanup(long now)
    {
        long next = CLEAN_AT.get();
        if ((now < next) || !CLEAN_AT.compareAndSet(next, now + 60000)) return;
        LINKS.entrySet().removeIf(e -> now - e.getValue().seen() > 300000);
        OBSERVED.entrySet().removeIf(e -> now - e.getValue().seen() > 300000);
        CLAIMS.entrySet().removeIf(e -> e.getValue().until() <= now);
    }

    static void observe(List<Player> members, long now)
    {
        for (Player member : members)
        {
            boolean dead = member.isDead(), blessed = protectedByNoblesse(member);
            OBSERVED.compute(member.getObjectId(), (id, old) -> {
                long urgent = old == null ? 0 : old.urgentUntil();
                if ((old != null) && !dead && (old.dead() || (old.blessed() && !blessed))) urgent = now + 20000;
                if (blessed) urgent = 0;
                return new Observation(dead, blessed, urgent, now);
            });
            if (dead || blessed) CLAIMS.remove(member.getObjectId());
        }
    }

    private static boolean eligible(Player caster)
    {
        Link link = LINKS.get(caster.getObjectId());
        return (link != null) && caster.isNoble() && caster.isInParty() && !caster.isDead() && !caster.isTeleporting()
            && !caster.isInOlympiadMode() && !caster.isOnEvent() && !caster.isInDuel()
            && caster.getParty().getMembers().stream().anyMatch(p -> p.getObjectId() == link.owner()) && PhantomPersonalNoblesse.allows(caster, BLESSING);
    }

    private static boolean recent(Player player, long now)
    {
        Observation state = OBSERVED.get(player.getObjectId());
        return (state != null) && state.urgentUntil() > now;
    }

    private static boolean pending(Player target, long now)
    {
        Claim claim = CLAIMS.get(target.getObjectId());
        if (claim == null) return false;
        if ((claim.until() > now) && target.isInParty())
        {
            Player caster = target.getParty().getMembers().stream().filter(p -> p.getObjectId() == claim.caster()).findFirst().orElse(null);
            if ((caster != null) && !caster.isDead() && !caster.isTeleporting()
                && ((now - claim.started() <= 1000) || caster.isCastingNow())) return true;
        }
        CLAIMS.remove(target.getObjectId(), claim);
        return false;
    }

    private static boolean reachable(Player caster, Player target, Skill skill)
    {
        return (caster == target) || (PhantomCompanionManager.inReach(caster, target, caster.getMagicalAttackRange(skill))
            && GeoEngine.getInstance().canSeeTarget(caster, target));
    }

    /** Prefer a ready support in reach; any noble companion may cover for one that is busy or absent. */
    private static boolean supportCanCover(Player caster, Player target)
    {
        Link own = LINKS.get(caster.getObjectId());
        if ((target == caster) || ((own != null) && own.support())) return false;
        for (Player member : caster.getParty().getMembers())
        {
            Link link = LINKS.get(member.getObjectId());
            if ((member == caster) || (link == null) || !link.support() || !eligible(member) || member.isCastingNow() || member.isSitting()) continue;
            Skill skill = PhantomSupport.usable(member, BLESSING);
            if ((skill != null) && reachable(member, target, skill)) return true;
        }
        return false;
    }

    private static boolean cast(Player caster, Player target, long now)
    {
        if (!eligible(caster) || target.isDead() || (target.getInstanceId() != caster.getInstanceId()) || !caster.getParty().containsPlayer(target)
            || protectedByNoblesse(target)) return false;
        Skill skill = PhantomSupport.usable(caster, BLESSING);
        if ((skill == null) || !reachable(caster, target, skill) || supportCanCover(caster, target)) return false;
        synchronized (CLAIMS)
        {
            if (protectedByNoblesse(target) || pending(target, now)) return false;
            if (caster.isSitting()) { caster.standUp(); return true; }
            caster.setTarget(target);
            if (!PhantomCompat.useMagic(caster, skill, false, false)) return false;
            CLAIMS.put(target.getObjectId(), new Claim(caster.getObjectId(), now, now + Math.max(2000, skill.getHitTime() + 2000)));
            return true;
        }
    }

    /** Fighters restore their own protection before attacking; a failed/unaffordable cast never freezes the actor. */
    static boolean self(Player caster, long now)
    {
        if (!eligible(caster) || protectedByNoblesse(caster)) return false;
        if (caster.isCastingNow() || pending(caster, now)) return true;
        if (caster.hasAI() && caster.getAI().getIntention() == Intention.CAST) caster.getAI().setIntentionActive();
        return cast(caster, caster, now);
    }

    static boolean maintain(Player caster, long now)
    {
        return self(caster, now) || protectParty(caster, true, now) || protectParty(caster, false, now);
    }

    /** Recent revivals take precedence over ordinary maintenance. Emergency healing still comes first. */
    static boolean protectParty(Player caster, boolean recentOnly, long now)
    {
        if (!eligible(caster) || caster.isCastingNow()) return false;
        List<Player> members = caster.getParty().getMembers();
        observe(members, now);
        int healBelow = recentOnly ? 50 : 85;
        for (Player member : members)
            if (!member.isDead() && (member.getInstanceId() == caster.getInstanceId())
                && (member.getCurrentHp() * 100 < member.getMaxHp() * (double) healBelow)) return false;
        List<Player> targets = new ArrayList<>(members);
        targets.sort(Comparator.comparingInt((Player p) -> recent(p, now) ? 0 : !PhantomManager.getInstance().isPhantom(p) ? 1 : p == caster ? 2 : 3)
            .thenComparingInt(Player::getObjectId));
        for (Player target : targets)
            if ((!recentOnly || recent(target, now)) && cast(caster, target, now)) return true;
        return false;
    }
}
