Нублес личных напарников: текущий владелец должен быть дворянином (0511).

Два независимых пути ошибки:
1. PartyNoblesse/PhantomPartyNoblesse допускали сохранённого noble-кастера
   при неноблес-владельце. Повторный summon/load не сбрасывает флаг.
2. Native PhantomSupport.selfBuffSet берёт ROLE_*_GROUP, включая1323,
   и selfBuff накладывает эффекты напрямую, без noble flag/выученного навыка.

Исправление:
- Один Epilogue helper PhantomPersonalNoblesse проверяет текущую запись
  Companion.ownerId через штатный isOwner(member,template) в реальной пати.
  Лидер партии и постоянный owner из пула не заменяют текущего владельца.
- Каст/повторное восстановление навыка и личный preset учитывают это право.
- Неверный synchronize удаляет старую привязку, а cast повторно проверяет
  допуск до доступа к цели/эффектам/инвентарю.
- Штатные игроки, мировые hunters и reaper не получают нового запрета.
  Получатель благословения не обязан быть дворянином.
- Исторический noble flag/1323/уже полученные эффекты не удаляются;
  новая личная автоматизация при неноблес-владельце ими не авторизуется.
  Native world-level grant в PhantomOlympiad и правила самого навыка прежние.

Исходная врезка только patches/epilogue/0511; phantoms/core не изменён
и его полная дополнительная копия не создана. Для переходного local-plugin
нужны отдельные адаптеры PartyNoblesse и PartyBuffs, вызывающие тот же helper.
Иначе старый preset/grant остаётся независимым путём. Они передаются Root
отдельно от Native patch, без правок sharedSDK.

Проверка в отдельной пустой JVM JDK27:
1. Применить0511 штатным byte-wise patcher после копирования core/epilogue;
   добавить PhantomPersonalNoblesse, скомпилировать изменённые два Native
   класса. Для local-plugin также скомпилировать оба адаптера.
2. Скомпилировать PersonalNoblesseCheck.java и OwnerContextsAgent.java.
   OwnerContextsAgent.jar manifest: Premain-Class: OwnerContextsAgent.
3. Запускать с двумя premain agent:
   -javaagent:<NoWorldAgent.jar> (из ../ready-source, блокирует JDBC/мир)
   -javaagent:<OwnerContextsAgent.jar>
   --enable-final-field-mutation=ALL-UNNAMED
   -cp <testclasses;candidateclasses;Native libs/*;legacy module>
   org.l2jmobius.gameserver.managers.phantom.PersonalNoblesseCheck fixed
   Для baseline исключить candidateclasses и передать baseline.

OwnerContextsAgent в test JVM заменяет ТОЛЬКО getInstance пяти сервисов
заранее заданными объектами в памяти, чтобы не запускать их конструкторы,
SQL, загрузку мира и timers. Не менять им production-классы/живой процесс.
Реальные isOwner, policy, eligible, Native selfBuffSet и legacy preset
исполняются без переписывания. Actor flags/party и Skill/Table — fixtures;
проверка не исполняет applyEffects, реальную трату MP/реагента, native save,
очередь каста или полный старт сервера. Шесть реальных маршрутов classGroup
проверяются с table fixture, содержащим1323 и обычный1040.

Baseline20/fixed37 условий: старый допуск и preset воспроизведены,
флаги владельца/повторная привязка/передача в той же пати/отзыв/мировой
охотник/обычный человек/неизвестный phantom, обычный бафф сохранён.
Полная module-интеграция и живой случай70уровня остаются Root/игровой проверке.
