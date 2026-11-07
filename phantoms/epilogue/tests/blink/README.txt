0510 — Blink/AURA без врагов

Skill1448 уже имеет правильные отдельные GENERAL damage+stun и SELF Blink.
Player.useMagic направляет AURA на самого кастера; onMagicLaunchedTimer допускает
пустой список внешних целей. Но callSkill возвращался раньше activateSkill,
поэтому собственный прыжок терялся. Сам Blink-handler врага не требует.

Изменён только начальный guard callSkill. Null отвергается, empty допускается
только для Native AURA-family с реальными SELF-эффектами. Обычные target-skills
и empty безSELF остаются прежними. Фиктивный caster в GENERAL-targets не добавляется.
Поздний guard после фильтра raid-heal/buff сохранён, MP/reuse/souls/геодата/
дальность/таймеры/навыки/стоимости не менялись. Skill.activateSkill как прежде
применяет GENERAL каждому врагу и SELF ровно один раз вне target-loop.

В Native данных AURA+SELF:368Vengeance/885AirBlink/1448Blink/7078TestBlink/
8240ObliterationStorm. Исправление семантическое, не по ID и не по нику персонажа.

BlinkPathCheck +endpoints — целевая NoWorld-fixture, не общий server-runner.
Реально исполняет callSkill -> Native Skill.activateSkill/applyEffects/
applyEffectScope, Blink/PhysicalSoulDamage/Stun/ImmobileBuff из Native sources.
Baseline77/fixed77:0/1/3врага, одинSELF, damage+stun каждому врагу и не кастеру;
Warp628, Vengeance368, все TargetType, null/emptyбезSELF/ONE, иммунитеты/неуязвимость.
JDBC guard активно проверен. Фикс после реального module-member-merge также
прошёл77условий, fingerprint callSkill совпал с проверенным исходником.

Нужны JDK27, Native205 libs/конфиги/skill-data и существующий NoWorldAgent
из ../ready-source. В отдельный datapack скопировать требуемые Native config,
statBonus.xml/CharacterTemplates/Enchant/Categories/xsd, изXML оставить1448/628/368.
Компилировать fixture, endpoints и Native effect sources Blink/PhysicalSoulDamage/
Stun/ImmobileBuff в отдельный каталог. Перед Native libs поставить проверяемый
Creature.class и endpoint classes; Native Skill.class должен остаться изGameServer.jar.
Использовать -Xverify:all -Djava.awt.headless=true
 --sun-misc-unsafe-memory-access=allow
 --enable-final-field-mutation=ALL-UNNAMED -javaagent:<NoWorldAgent.jar>.
Класс isolated.blink.BlinkPathCheck <fixed true|false> <Native effect-classpath>.

Границы: constructorless actors/stats, GeoEngine/AI/network/setXYZ, deterministic
RNG midpoint и continuous scheduling sink контролируются. Реальный маршрут
по карте, клиентский рендер и полный useMagic цикл затрат не выполняются;
проверка фикса не считается живой проверкой координат или MP/reuse.
