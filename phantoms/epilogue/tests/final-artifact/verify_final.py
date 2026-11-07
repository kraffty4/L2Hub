"""Изолированный verifier final GameServer.jar: компилирует ТОЛЬКО fixtures, не product sources."""
from __future__ import annotations
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

HERE = Path(__file__).resolve().parent
SOURCES = HERE / 'sources'
BANK_NAMES = ('LocalReplyBank.xml','LocalCityDialogues.xml','LocalPartyDialogues.xml',
              'LocalPartyLongDialogues.xml','LocalClanDialogues.xml','LocalHeroDialogues.xml',
              'LocalHeroOpeners.txt','LocalDialogueBindings.xml')
FIXTURE_FILES = ('ArtifactProvenanceCheck.java','FinalBytecodeCheck.java','FinalNobleOwnerCheck.java',
                 'GmShopEnchantPolicyCheck.java','DialogueDataCheck.java','DialogueRuntimeCheck.java')
AGENT_FILES = ('NoWorldAgent.java','NobleActorContextsAgent.java','DialogueContextsAgent.java')
REQUIRED = [
 'org.l2jmobius.gameserver.GameServer',
 'org.l2jmobius.gameserver.entity.actor.Player',
 'org.l2jmobius.gameserver.entity.item.EtcItem',
 'org.l2jmobius.gameserver.entity.groups.Party',
 'org.l2jmobius.gameserver.entity.clan.Clan',
 'org.l2jmobius.gameserver.data.xml.ItemData',
 'org.l2jmobius.gameserver.data.xml.EnchantItemData',
 'org.l2jmobius.gameserver.data.xml.MultisellData',
 'org.l2jmobius.gameserver.data.xml.BuyListData',
 'org.l2jmobius.gameserver.managers.GmShopEnchantPolicy',
 'org.l2jmobius.gameserver.managers.GmShopEnchantPolicy$Settings',
 'org.l2jmobius.gameserver.mechanics.multisell.PreparedListContainer',
 'org.l2jmobius.gameserver.network.clientpackets.MultiSellChoose',
 'org.l2jmobius.gameserver.network.clientpackets.RequestBuyItem',
 'org.l2jmobius.gameserver.network.serverpackets.BuyList',
 'org.l2jmobius.gameserver.network.serverpackets.ExBuySellList',
 'org.l2jmobius.gameserver.managers.phantom.PhantomPersonalNoblesse',
 'org.l2jmobius.gameserver.managers.phantom.PartyNoblesse',
 'org.l2jmobius.gameserver.managers.phantom.PartyNoblesse$Link',
 'org.l2jmobius.gameserver.managers.phantom.PhantomPartyNoblesse',
 'org.l2jmobius.gameserver.managers.phantom.PhantomPartyNoblesse$Link',
 'org.l2jmobius.gameserver.managers.phantom.PartyBuffs',
 'org.l2jmobius.gameserver.managers.phantom.PhantomSupport',
 'org.l2jmobius.gameserver.managers.phantom.PhantomData',
 'org.l2jmobius.gameserver.managers.phantom.PhantomManager',
 'org.l2jmobius.gameserver.managers.phantom.PhantomCompanionManager',
 'org.l2jmobius.gameserver.managers.phantom.PhantomCompanionManager$Companion',
 'org.l2jmobius.gameserver.managers.phantom.LocalDialogueBindings',
 'org.l2jmobius.gameserver.managers.phantom.LocalCityDialogues',
 'org.l2jmobius.gameserver.managers.phantom.LocalCityDialogues$Controller',
 'org.l2jmobius.gameserver.managers.phantom.LocalCityDialogues$Bank',
 'org.l2jmobius.gameserver.managers.phantom.LocalCityDialogues$Session',
 'org.l2jmobius.gameserver.managers.phantom.LocalReplyBank',
 'org.l2jmobius.gameserver.managers.phantom.LocalReplyBank$Snapshot',
 'org.l2jmobius.gameserver.managers.phantom.LocalPartyDialogues',
 'org.l2jmobius.gameserver.managers.phantom.LocalClanDialogues',
 'org.l2jmobius.gameserver.managers.phantom.LocalHeroDialogues',
 'org.l2jmobius.gameserver.managers.phantom.LocalHeroDialogues$HeroGateway',
 'org.l2jmobius.gameserver.managers.phantom.PhantomChat',
 'org.l2jmobius.gameserver.managers.phantom.PhantomChatFlow',
]
FIXTURE_TOP = (
 'ArtifactProvenanceCheck','FinalBytecodeCheck','GuardSelfCheck',
 'org/l2jmobius/gameserver/managers/phantom/FinalNobleOwnerCheck',
 'org/l2jmobius/gameserver/managers/GmShopEnchantPolicyCheck',
 'org/l2jmobius/gameserver/managers/phantom/DialogueDataCheck',
 'org/l2jmobius/gameserver/managers/phantom/DialogueRuntimeCheck')

class VerificationError(RuntimeError): pass
def demand(ok, reason):
    if not ok: raise VerificationError(reason)
def sha(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''): h.update(chunk)
    return h.hexdigest()
def json_write(path, value):
    Path(path).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def iso(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
def hex_value(value, size): return bool(re.fullmatch('[0-9a-fA-F]{'+str(size)+'}',value or '')) and set(value.lower())!={'0'}
def real(path): return Path(path).expanduser().resolve(strict=True)
def pinned_sha(path, expected, label):
    demand(hex_value(expected,64),'Invalid exact expected SHA256: '+label)
    actual=sha(path);demand(actual==expected.lower(),'Pinned bytes differ: '+label);return actual
def check_banks(data, expected):
    demand(set(expected)==set(BANK_NAMES),'Expected manifest must contain exactly the eight bank/metadata names')
    for name,digest in expected.items():
        demand((data/name).is_file(),'Expected bank missing: '+name)
        pinned_sha(data/name,digest,name)
def overlap(a,b): return a==b or a.is_relative_to(b) or b.is_relative_to(a)
def new_out(path, protected=()):
    out=Path(path).expanduser().resolve()
    demand(not any(overlap(out,p) for p in protected),'Output must not overlap inputs/source folders')
    demand(not out.exists() or out.is_dir() and not any(out.iterdir()),'Output must be new or empty; never delete/reuse a previous testclasses tree')
    out.mkdir(parents=True,exist_ok=True)
    return out
def clean_env():
    env=os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','CLASSPATH'):env.pop(key,None)
    return env
def run(cmd, cwd, log, timeout=90):
    flags=(subprocess.CREATE_NO_WINDOW|subprocess.BELOW_NORMAL_PRIORITY_CLASS) if os.name=='nt' else 0
    try:
        result=subprocess.run([str(x) for x in cmd],cwd=cwd,env=clean_env(),capture_output=True,timeout=timeout,creationflags=flags)
    except subprocess.TimeoutExpired as error:
        Path(log).write_bytes((error.stdout or b'')+(error.stderr or b''))
        raise VerificationError('Own test process timed out: '+str(log)) from error
    Path(log).write_bytes(result.stdout+result.stderr)
    return result
def checked(cmd,cwd,log,timeout=90,marker=None):
    result=run(cmd,cwd,log,timeout)
    text=(result.stdout+result.stderr).decode('utf-8',errors='replace')
    demand(result.returncode==0,f'Process exit{result.returncode}: {log}\n{text[-1800:]}')
    if marker:demand(marker in text,'No expected completion marker '+marker+': '+str(log))
    return [line for line in text.splitlines() if line.startswith(('PASS ','CLASS\t','STABLE_ROW\t'))]
def jdk(path):
    home=real(path);suffix='.exe' if os.name=='nt' else ''
    tools={name:home/'bin'/(name+suffix) for name in ('java','javac','jar')}
    demand(all(p.is_file() for p in tools.values()),'JDK home needs java/javac/jar')
    return tools
def jar_contents(path):
    with zipfile.ZipFile(path) as z:
        names=z.namelist();demand(len(names)==len(set(names)),'Duplicate ZIP entries in '+str(path))
        return set(names)
def regular_libs(folder,target):
    kept=[];excluded=[]
    for lib in sorted(folder.glob('*.jar'),key=lambda p:p.name.casefold()):
        lower=lib.name.lower()
        if lib.resolve()==target or lower.startswith(('gameserver','loginserver')) or 'l2localmodule' in lower:
            excluded.append(dict(file=str(lib),reason='target/server/module excluded from ordinary libs'));continue
        with zipfile.ZipFile(lib) as z:
            names=z.namelist()
            demand(not any(n.startswith('org/l2jmobius/gameserver/') and n.endswith('.class') for n in names),'Ordinary lib contains shadow game classes: '+str(lib))
            demand(not any(n.startswith(('owned-core/','patch-custom/')) for n in names),'Legacy module disguised as ordinary lib: '+str(lib))
        kept.append(lib.resolve())
    demand(kept,'No ordinary libraries found')
    return kept,excluded
def forbid_shadow(testclasses,jar_entries,allowlist=True):
    generated=[]
    for path in testclasses.rglob('*.class'):
        entry=path.relative_to(testclasses).as_posix();generated.append(entry)
        demand(entry not in jar_entries,'Fixture shadows actual JAR class: '+entry)
        if allowlist:
            top=entry[:-6].split('$',1)[0]
            demand(top in FIXTURE_TOP,'Unexpected class in fresh testclasses (production helper recompiled?): '+entry)
    demand(generated,'No compiled fixture classes')
    return sorted(generated)
def compile_fixtures(tools,cp,output,files,log):
    output.mkdir(exist_ok=False);empty=output.parent/('empty-sourcepath-'+output.name);empty.mkdir(exist_ok=False)
    checked([tools['javac'],'-encoding','UTF-8','-proc:none','-implicit:none','-sourcepath',empty,'-cp',cp,'-d',output,*files],output.parent,log)
def agents(tools,out):
    result={}
    for source in AGENT_FILES:
        name=Path(source).stem;folder=out/(name+'-agentclasses');folder.mkdir();
        checked([tools['javac'],'-encoding','UTF-8','-proc:none','-d',folder,SOURCES/source],out,out/('compile-'+name+'.log'))
        demand(all(p.name.startswith(name) for p in folder.rglob('*.class')),'Unexpected implementation in test agent')
        manifest=out/(name+'.mf');manifest.write_text(f'Premain-Class: {name}\nCan-Retransform-Classes: true\n\n',encoding='utf-8')
        temp=out/'jar-tmp';temp.mkdir(exist_ok=True);jar=out/(name+'.jar')
        checked([tools['jar'],f'-J-Djava.io.tmpdir={temp}','cfm',jar,manifest,'-C',folder,'.'],out,out/('jar-'+name+'.log'))
        result[name]=jar
    return result
def class_manifest(target,names,out):
    rows=[]
    with zipfile.ZipFile(target) as z:
        for name in names:
            entry=name.replace('.','/')+'.class'
            demand(entry in z.namelist(),'Required production class absent from finalJar: '+name)
            rows.append((name,hashlib.sha256(z.read(entry)).hexdigest()))
    file=out/'class-manifest.tsv';file.write_text(''.join(n+'\t'+h+'\n' for n,h in rows),encoding='utf-8')
    return file,dict(rows)
def final_snapshot(data,jar,libs):
    paths=[jar,*libs,*(data/name for name in BANK_NAMES),data/'EnchantItemData.xml',
           *(data/'multisell/custom'/name for name in ('50039931.xml','50039977.xml','90003000.xml')),data/'buylists/0009931.xml']
    items=sorted((data/'stats/items').rglob('*.xml'))
    demand(items,'Native item catalogue required under game-data/stats/items')
    paths+=items
    if (data/'SchemeBufferSkills.xml').exists():paths.append(data/'SchemeBufferSkills.xml')
    demand(all(p.is_file() for p in paths),'Missing bank/metadata/Native catalogue file')
    return {str(p):sha(p) for p in paths}
def source_binding(jar,source_commit):
    source_commit=source_commit.lower()
    with zipfile.ZipFile(jar) as z:
        text=z.read('META-INF/MANIFEST.MF').decode('utf-8',errors='replace') if 'META-INF/MANIFEST.MF' in z.namelist() else ''
    text=text.replace('\r\n','\n');attrs={}
    for line in text.splitlines():
        if ': ' in line:key,value=line.split(': ',1);attrs[key.lower()]=value
    candidates={k:v for k,v in attrs.items() if k in ('git-commit','source-commit','l2hub-source-commit','build-revision')}
    commits={k:v.lower() for k,v in candidates.items() if re.fullmatch('[0-9a-fA-F]{7,40}',v)}
    for value in commits.values():
        demand(source_commit.startswith(value) or value.startswith(source_commit),'Source commit differs from embedded manifest')
    return dict(source_commit=source_commit,embedded_commit_attributes=commits,non_git_build_attributes={k:v for k,v in candidates.items()if k not in commits},
                provenance='embedded_and_sha_pinned' if commits else 'caller_receipt_bound_to_expected_jar_sha; source commit not embedded/proved by JAR')
def verify(args):
    target=real(args.final_jar);data=real(args.game_data);libdir=real(args.libs)
    demand(hex_value(args.expected_sha,64),'Need exact non-placeholder expected JAR SHA256')
    demand(hex_value(args.source_commit,40),'Need exact40hex source commit from final build receipt')
    demand(target.is_file() and target.suffix.lower()=='.jar','Final JAR required')
    pinned_sha(target,args.expected_sha,'final JAR before any compile/run')
    expected=json.loads(real(args.expected_banks).read_text('utf-8-sig'));expected=expected.get('banks',expected)
    check_banks(data,expected)
    tools=jdk(args.java_home);entries=jar_contents(target);libs,excluded=regular_libs(libdir,target)
    out=new_out(args.out,(data,libdir,SOURCES,target));report=dict(status='running',started_utc=iso(),final_target_qa_passed=False,
       target=str(target),expected_jar_sha256=args.expected_sha.lower(),data_root=str(data),source=source_binding(target,args.source_commit),
       ordinary_libraries=[dict(file=str(p),sha256=sha(p)) for p in libs],excluded_libraries=excluded,
       fixture_sources={p:sha(SOURCES/p) for p in (*FIXTURE_FILES,*AGENT_FILES)},production_helpers_compiled=False,tests=[])
    json_write(out/'report.json',report)
    try:
        checked([tools['javac'],'-version'],out,out/'jdk.log');version=(out/'jdk.log').read_text('utf-8',errors='replace')
        match=re.search(r'javac\s+(\d+)',version);demand(match and int(match.group(1))>=27,'JDK27+ required for fixture classfile API')
        before=final_snapshot(data,target,libs);json_write(out/'inputs-before.json',before)
        manifest,classes=class_manifest(target,REQUIRED,out);report['expected_class_resources']=classes
        cp=os.pathsep.join(map(str,[target,*libs]));tc=out/'fresh-testclasses'
        compile_fixtures(tools,cp,tc,[SOURCES/f for f in FIXTURE_FILES],out/'compile-fixtures.log')
        report['fixture_classes']=forbid_shadow(tc,entries);ag=agents(tools,out)
        runtime_cp=os.pathsep.join(map(str,[tc,target,*libs]));report['runtime_classpath']=[str(tc),str(target),*map(str,libs)]
        provenance=checked([tools['java'],'-Xmx256m','-cp',runtime_cp,'ArtifactProvenanceCheck',target,manifest],out,out/'provenance.log',marker='PASS ArtifactProvenanceCheck')
        report['code_source_resource_proof']=provenance
        def execute(label,main,tail=(),actor_agent=None,ini=None):
            scratch=out/'scratch'/label;(scratch/'data/stats/items').mkdir(parents=True);(scratch/'config/Custom').mkdir(parents=True)
            shutil.copyfile(data/'LocalDialogueBindings.xml',scratch/'data/LocalDialogueBindings.xml')
            if ini:(scratch/'config/Custom/GmShopEnchantScrolls.ini').write_text(ini,encoding='utf-8')
            command=[tools['java'],'-Xmx256m','--enable-final-field-mutation=ALL-UNNAMED',f'-javaagent:{ag["NoWorldAgent"]}']
            if actor_agent:command.append(f'-javaagent:{ag[actor_agent]}')
            log=out/(label+'.log');lines=checked([*command,'-cp',runtime_cp,main,*tail],scratch,log,marker='PASS ')
            row=dict(test=label,passed=True,log=str(log),summary=[x for x in lines if x.startswith('PASS ')])
            report['tests'].append(row);json_write(out/'report.json',report)
            return lines
        execute('bytecode-bindings','FinalBytecodeCheck')
        execute('noble-current-owner','org.l2jmobius.gameserver.managers.phantom.FinalNobleOwnerCheck',actor_agent='NobleActorContextsAgent')
        stable={};count=0
        for mode in ('default','crystal','enhanced','ancient','divine','special','all','off'):
            flags={name:mode in (name,'all') for name in ('crystal','enhanced','ancient','divine','special')}
            ini='GmShopEnchantPolicyEnabled = '+('False' if mode=='off' else 'True')+'\n'
            ini+=''.join(f'GmShopAllow{name.title()}Scrolls = {value}\n' for name,value in flags.items())
            lines=execute('enchant-'+mode,'org.l2jmobius.gameserver.managers.GmShopEnchantPolicyCheck',(data,mode),ini=ini)
            for line in lines:
                if line.startswith('STABLE_ROW\t'):
                    _,m,catalog,id,signature=line.split('\t');key=(catalog,signature)
                    demand(key not in stable or stable[key]==int(id),'Entry ID changed across final-JAR modes: '+str(key));stable[key]=int(id);count+=1
        report['stable_shop_entries']=dict(signatures=len(stable),cross_mode_rows=count)
        execute('dialogue-readers','org.l2jmobius.gameserver.managers.phantom.DialogueDataCheck',(data,))
        execute('dialogue-runtime','org.l2jmobius.gameserver.managers.phantom.DialogueRuntimeCheck',(data,),actor_agent='DialogueContextsAgent')
        demand(final_snapshot(data,target,libs)==before,'Final JAR/data/libs changed during verification')
        forbid_shadow(tc,entries);report.update(status='passed',finished_utc=iso(),final_target_qa_passed=True,
            inputs_unchanged=True,limits=['Only requested JAR/data verifier scope; no actual world/server/database/client startup or installation',
                'Actors/world registries/scheduler/packets are controlled fixtures; no real MP/payment/save or network serialization proof',
                'Eight frozen banks and supplied metadata checked; full residual literary rewrite not claimed'])
        json_write(out/'report.json',report);print('PASS final-JAR scoped verification:',out/'report.json');return 0
    except Exception as error:
        report.update(status='failed',finished_utc=iso(),error=str(error),final_target_qa_passed=False);json_write(out/'report.json',report);raise

def selftest(args):
    tools=jdk(args.java_home);out=new_out(args.out,(SOURCES,));steps=[]
    # Синтетический example type не является игровым helper и никогда не используется как final target.
    sample=out/'sample-src/verifier_sample/PinnedArtifactType.java';sample.parent.mkdir(parents=True)
    sample.write_text('package verifier_sample; public final class PinnedArtifactType { public static int value(){return42;} }'.replace('return42','return 42'),encoding='utf-8')
    build=out/'sample-build';build.mkdir();checked([tools['javac'],'-proc:none','-d',build,sample],out,out/'sample-compile.log')
    temp=out/'sample-jar-tmp';temp.mkdir()
    target=out/'synthetic-example-only.jar';checked([tools['jar'],f'-J-Djava.io.tmpdir={temp}','cf',target,'-C',build,'.'],out,out/'sample-jar.log')
    tc=out/'fresh-testclasses';compile_fixtures(tools,'',tc,[SOURCES/'ArtifactProvenanceCheck.java',SOURCES/'FinalBytecodeCheck.java',SOURCES/'GuardSelfCheck.java'],out/'compile-infrastructure.log')
    entries=jar_contents(target);forbid_shadow(tc,entries)
    manifest,_=class_manifest(target,['verifier_sample.PinnedArtifactType'],out)
    checked([tools['java'],'-cp',os.pathsep.join(map(str,[tc,target])),'ArtifactProvenanceCheck',target,manifest],out,out/'example-provenance.log',marker='PASS ArtifactProvenanceCheck');steps.append('synthetic positive CodeSource/resource/SHA proof')
    def rejects(name,fn):
        try:fn()
        except VerificationError:steps.append(name);return
        raise VerificationError('Selftest negative control unexpectedly accepted: '+name)
    receipt=source_binding(target,'1'*40)
    demand(not receipt['embedded_commit_attributes'] and receipt['provenance'].startswith('caller_receipt'),'Nonembedded source provenance must not be claimed embedded')
    mismatch=out/'synthetic-wrong-commit-only.jar'
    with zipfile.ZipFile(mismatch,'w') as z:
        z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nGit-Commit: '+'2'*40+'\n\n')
    rejects('embedded Git mismatch rejected by actual source binding',lambda:source_binding(mismatch,'1'*40))
    revision=out/'synthetic-non-git-revision-only.jar'
    with zipfile.ZipFile(revision,'w') as z:z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nBuild-Revision: 0.5.205\n\n')
    demand(source_binding(revision,'1'*40)['non_git_build_attributes'].get('build-revision')=='0.5.205','Numeric native revision is not an embedded Git commit')
    steps.append('source commit provenance separates pinned receipt, embedded Git, and native numeric revision')
    pinned_sha(target,sha(target),'synthetic example')
    rejects('wrong expected JAR SHA rejected by actual preflight',lambda:pinned_sha(target,'f'*64,'synthetic example wrong pin'))
    bankdir=out/'synthetic-bank-files';bankdir.mkdir()
    for name in BANK_NAMES:(bankdir/name).write_text('verifier sample only: '+name,encoding='utf-8')
    bankpins={name:sha(bankdir/name)for name in BANK_NAMES};check_banks(bankdir,bankpins)
    (bankdir/BANK_NAMES[0]).write_text('changed sample',encoding='utf-8')
    rejects('changed bank rejected by actual preflight',lambda:check_banks(bankdir,bankpins))
    shadow=tc/'verifier_sample/PinnedArtifactType.class';shadow.parent.mkdir();shutil.copyfile(build/'verifier_sample/PinnedArtifactType.class',shadow)
    rejects('testclasses shadow rejected before JVM',lambda:forbid_shadow(tc,entries))
    # Второй JVM тоже обязан отвергнуть затенение, даже если caller обошёл Python gate.
    bad=run([tools['java'],'-cp',os.pathsep.join(map(str,[tc,target])),'ArtifactProvenanceCheck',target,manifest],out,out/'example-shadow-rejected.log')
    demand(bad.returncode!=0 and b'Shadow class' in bad.stderr,'Java CodeSource shadow control failed');steps.append('Java independent shadow rejection')
    rejects('nonempty output refused',lambda:new_out(out))
    safe=out/'safe-empty';safe.mkdir();rejects('output cannot overwrite input tree',lambda:new_out(safe,(safe,)))
    ag=agents(tools,out)
    checked([tools['java'],f'-javaagent:{ag["NoWorldAgent"]}','-cp',tc,'GuardSelfCheck'],out,out/'guard-selfcheck.log',marker='PASS GuardSelfCheck');steps.append('real JDBC DriverManager boundary blocked')
    report=dict(status='runner_selftest_passed',steps=steps,final_target_qa_passed=False,
        native_target_run=False,example_kind='synthetic verifier-only example; not GameServer and not source-ready product',
        infrastructure_compiled=['ArtifactProvenanceCheck','FinalBytecodeCheck','GuardSelfCheck',*map(lambda x:Path(x).stem,AGENT_FILES)],
        native_fixture_compilation='deferred until exact final JAR is supplied; runner compiles only fixture sources against that JAR',
        finished_utc=iso())
    json_write(out/'selftest.json',report);print('PASS runner selftest (final-target QA NOT RUN):',out/'selftest.json');return 0

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--selftest',action='store_true')
    for key in ('final-jar','game-data','libs','expected-sha','source-commit'):p.add_argument('--'+key)
    p.add_argument('--expected-banks',default=str(HERE/'expected-banks.json'));p.add_argument('--java-home',required=True);p.add_argument('--out',required=True)
    a=p.parse_args()
    if a.selftest:
        demand(not any(getattr(a,k) for k in ('final_jar','game_data','libs','expected_sha','source_commit')),'Selftest must not be confused with a supplied final target')
        return selftest(a)
    demand(all(getattr(a,k) for k in ('final_jar','game_data','libs','expected_sha','source_commit')),'Final mode needs final-jar/game-data/libs/expected-sha/source-commit')
    return verify(a)
if __name__=='__main__':
    try:sys.exit(main())
    except Exception as e:print('FAIL:',e,file=sys.stderr);sys.exit(1)
