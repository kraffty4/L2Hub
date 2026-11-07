package org.l2jmobius.gameserver.managers;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.DriverManager;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.l2jmobius.gameserver.config.ServerConfig;
import org.l2jmobius.commons.config.ThreadConfig;
import org.l2jmobius.gameserver.config.custom.CommunityBoardConfig;
import org.l2jmobius.gameserver.data.xml.*;
import org.l2jmobius.gameserver.entity.actor.*;
import org.l2jmobius.gameserver.entity.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.entity.item.*;
import org.l2jmobius.gameserver.entity.item.enchant.EnchantScroll;
import org.l2jmobius.gameserver.mechanics.buylist.*;
import org.l2jmobius.gameserver.mechanics.multisell.*;
import org.l2jmobius.gameserver.util.StatSet;
import org.l2jmobius.commons.network.buffer.WriteBuffer;
import org.l2jmobius.gameserver.network.serverpackets.BuyList;

/** Изолированная JVM: реальные EtcItem/EnchantScroll/XML-parser, без мира и БД. */
public final class GmShopEnchantPolicyCheck
{
    private static final sun.misc.Unsafe U;
    static
    {
        try { Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); U=(sun.misc.Unsafe)f.get(null); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static int checks;
    private static String mode;
    private static final Set<Integer> FORTUNA=Set.of(22000,22001,22002,22003,22004,22005,20515,20516);
    private static final Map<Integer,GmShopEnchantPolicy.Kind> EXPECTED=new LinkedHashMap<>();
    private static final Map<GmShopEnchantPolicy.Kind,int[]> GROUPS=Map.of(
        GmShopEnchantPolicy.Kind.NORMAL,new int[]{729,730,947,948,951,952,955,956,959,960},
        GmShopEnchantPolicy.Kind.BLESSED,new int[]{6569,6570,6571,6572,6573,6574,6575,6576,6577,6578},
        GmShopEnchantPolicy.Kind.CRYSTAL,new int[]{731,732,949,950,953,954,957,958,961,962},
        GmShopEnchantPolicy.Kind.ENHANCED,new int[]{20517,20518,22006,22007,22008,22009,22010,22011,22012,22013},
        GmShopEnchantPolicy.Kind.ANCIENT,new int[]{20519,20520,22014,22015,22016,22017},
        GmShopEnchantPolicy.Kind.DIVINE,new int[]{20521,20522,22018,22019,22020,22021},
        GmShopEnchantPolicy.Kind.SPECIAL,new int[]{13540,15346,15347,15348,15349,15350,15381,15382});

    private static void check(boolean ok,String why) { checks++; if(!ok)throw new AssertionError(why); }
    private static <T> T blank(Class<T> c) throws Exception { return c.cast(U.allocateInstance(c)); }
    private static void set(Object o,Class<?> c,String field,Object value) throws Exception
    { Field f=c.getDeclaredField(field);f.setAccessible(true);f.set(o,value); }
    private static Document xml(Path p) throws Exception
    {
        var factory=DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        return factory.newDocumentBuilder().parse(p.toFile());
    }
    private static List<Element> children(Node root,String name)
    {
        List<Element> out=new ArrayList<>();
        for(Node n=root.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element e&&e.getTagName().equals(name))out.add(e);
        return out;
    }
    private static boolean expected(int id)
    {
        var kind=EXPECTED.get(id);
        return mode.equals("off")||kind==null||kind==GmShopEnchantPolicy.Kind.NORMAL||kind==GmShopEnchantPolicy.Kind.BLESSED
            ||mode.equals("all")||kind.name().equalsIgnoreCase(mode);
    }
    private static Ingredient ingredient(int id,long count) { return new Ingredient(id,count,0,false,false); }
    private static Entry entry(int n,int id)
    { Entry e=new Entry(n);e.addProduct(ingredient(id,1));e.addIngredient(ingredient(57,123456));return e; }
    private static Npc npc(int id) throws Exception
    { NpcTemplate t=blank(NpcTemplate.class);set(t,NpcTemplate.class,"_id",id);Npc n=blank(Npc.class);set(n,Creature.class,"_template",t);return n; }
    private static BuyListHolder buyList(int owner)
    {
        BuyListHolder b=new BuyListHolder(7654321);if(owner>0)b.addAllowedNpc(owner);
        for(int id:EXPECTED.keySet())b.addProduct(new Product(b.getListId(),ItemData.getInstance().getTemplate(id),123456,-1,-1));
        return b;
    }
    private static ListContainer list(int owner)
    {
        ListContainer l=new ListContainer(7654321);if(owner>0)l.allowNpc(owner);
        int n=0;for(int id:EXPECTED.keySet())l.getEntries().add(entry(++n,id));return l;
    }
    private static void items(Path data) throws Exception
    {
        ServerConfig.DATAPACK_ROOT=new File(".");ThreadConfig.THREADS_FOR_LOADING=false;
        // Нет load() из рабочей игры. Только пустой собственный каталог и минимальные реальные EtcItem.
        ItemData instance=ItemData.getInstance();
        Map<Integer,ItemTemplate> templates=new HashMap<>();
        try(var paths=Files.walk(data.resolve("stats/items")))
        {
            for(Path path:paths.filter(p->p.toString().endsWith(".xml")).sorted().toList())
            {
                for(Element item:children(xml(path).getDocumentElement(),"item"))
                {
                    StatSet s=new StatSet();s.set("item_id",item.getAttribute("id"));s.set("name",item.getAttribute("name"));
                    // Для не-scroll предметов нужен только контроль сохранения товара, а не параметры оружия.
                    for(Element v:children(item,"set"))if(Set.of("etcitem_type","crystal_type","is_stackable","price","weight").contains(v.getAttribute("name")))
                        s.set(v.getAttribute("name"),v.getAttribute("val"));
                    EtcItem t=new EtcItem(s);templates.put(t.getId(),t);
                }
            }
        }
        ItemTemplate[] all=new ItemTemplate[Collections.max(templates.keySet())+1];
        templates.forEach((id,t)->all[id]=t);set(instance,ItemData.class,"_allTemplates",all);
        EnchantItemData.getInstance().parseDocument(xml(data.resolve("EnchantItemData.xml")),data.resolve("EnchantItemData.xml").toFile());
        check(EnchantItemData.getInstance().getScrolls().size()==60,"all60 actual Native enchant definitions parsed");
        for(var g:GROUPS.entrySet())for(int id:g.getValue())EXPECTED.put(id,g.getKey());
        check(EXPECTED.size()==60,"fixture Native205 IDs cover60 scrolls");
        for(var e:EXPECTED.entrySet())
        {
            check(GmShopEnchantPolicy.kind(e.getKey())==e.getValue(),"actual metadata category "+e);
            check(GmShopEnchantPolicy.allows(e.getKey())==expected(e.getKey()),"owner opt-in "+e);
        }
        int supports=0;
        for(Element support:children(xml(data.resolve("EnchantItemData.xml")).getDocumentElement(),"support"))
        {
            int id=Integer.parseInt(support.getAttribute("id"));supports++;
            check(GmShopEnchantPolicy.kind(id)==GmShopEnchantPolicy.Kind.NOT_SCROLL,"support item is not enchant scroll");
            check(GmShopEnchantPolicy.allows(id),"support item not removed");
        }
        check(supports==20,"all20 supports retained");
        check(GmShopEnchantPolicy.allows(57)&&GmShopEnchantPolicy.allows(-100),"ordinary goods/special currency unaffected");
    }
    private static void scope() throws Exception
    {
        int count=(int)EXPECTED.keySet().stream().filter(GmShopEnchantPolicyCheck::expected).count();
        Npc gm=npc(50010),ordinary=npc(30001);
        for(int owner:new int[]{0,30001,50010})
        {
            for(Npc context:new Npc[]{null,gm,ordinary})
            {
                ListContainer raw=list(owner);PreparedListContainer prepared=new PreparedListContainer(raw,false,null,context);
                boolean applies=owner==50010||context==gm;
                for(Entry e:prepared.getEntries())check(GmShopEnchantPolicy.canExchange(prepared,context,e)==(!applies||expected(e.getProducts().get(0).getItemId())),"stale/request guard scope");
                GmShopEnchantPolicy.filter(prepared,context);
                check(prepared.getEntries().size()==(applies?count:60),"NPC filtering scope");
                check(raw.getEntries().size()==60,"Prepared filtering never mutates original");
            }
        }
        ListContainer raw=list(30001);PreparedListContainer board=new PreparedListContainer(raw,false,null,null);board.setFromBoard(true);
        GmShopEnchantPolicy.filter(board,null);check(board.getEntries().size()==count,"AltB independent of NPC owners");
        Entry mixed=entry(90,729);mixed.addProduct(ingredient(22018,3));ListContainer bundle=new ListContainer(1);bundle.allowNpc(50010);bundle.getEntries().add(mixed);
        Entry input=entry(91,729);input.getIngredients().clear();input.addIngredient(ingredient(22018,2));bundle.getEntries().add(input);
        GmShopEnchantPolicy.filter(bundle,null);check(bundle.getEntries().contains(mixed)==expected(22018),"whole mixed-product row filtered");
        check(bundle.getEntries().contains(input)&&input.getIngredients().get(0).getItemCount()==2,"ingredients never forbidden/mutated");
        for(int owner:new int[]{0,30001,50010})
        {
            BuyListHolder b=buyList(owner);
            for(Npc target:new Npc[]{null,gm,ordinary})
            {
                Player p=blank(Player.class);set(p,Creature.class,"_target",target);set(p,Player.class,"_lastFolkNpc",gm);
                boolean applies=owner==50010||target==gm||target==null;
                var visible=GmShopEnchantPolicy.productsForViewer(b,p);
                check(visible.size()==(applies?count:60),"buylist current NPC wins over stale GM lastFolk");
                check(b.getProducts().size()==60,"viewer filtering never mutates catalog");
                for(Product product:b.getProducts())
                {
                    check(GmShopEnchantPolicy.canBuy(b,p,product.getItemId())==(!applies||expected(product.getItemId())),"buylist request guard matches viewer");
                    check(product.getPrice()==123456&&product.getCount()==0,"price/stock unchanged");
                }
            }
            GmShopEnchantPolicy.filter(b);check(b.getProducts().size()==(owner==50010?count:60),"buylist XML explicit GM scope");
        }
        // Не привязываемся к50010 в production: владелец может сменить NPC в штатном конфиге.
        CommunityBoardConfig.COMMUNITY_SHOP_NPC=50011;
        ListContainer custom=list(50011);GmShopEnchantPolicy.filter(custom,null);check(custom.getEntries().size()==count,"configured GM NPC respected");
        ListContainer old=list(50010);GmShopEnchantPolicy.filter(old,null);check(old.getEntries().size()==60,"old NPC no longer classified GM");
        CommunityBoardConfig.COMMUNITY_SHOP_NPC=50010;
    }
    private static Map<Integer,ListContainer> parser(MultisellData parser) throws Exception
    { Field f=MultisellData.class.getDeclaredField("_entries");f.setAccessible(true);return (Map<Integer,ListContainer>)f.get(parser); }
    private static void catalogs(Path data) throws Exception
    {
        MultisellData parser=blank(MultisellData.class);set(parser,MultisellData.class,"_entries",new HashMap<Integer,ListContainer>());
        for(String name:new String[]{"50039931","50039977","90003000"})
        {
            Path path=data.resolve("multisell/custom/"+name+".xml");Document doc=xml(path),normalized=xml(path);
            // Ожидаемый ordinal: только8 неизменных прежних non-scroll bans, без scroll flags.
            for(Element row:new ArrayList<>(children(normalized.getDocumentElement(),"item")))
                if(children(row,"production").stream().anyMatch(e->FORTUNA.contains(Integer.parseInt(e.getAttribute("id")))))row.getParentNode().removeChild(row);
            List<Element> rows=children(normalized.getDocumentElement(),"item");List<Integer> keep=new ArrayList<>();int entryId=0;
            for(Element row:rows)
            {
                entryId++;boolean allowed=true;
                for(Element out:children(row,"production"))allowed&=expected(Integer.parseInt(out.getAttribute("id")));
                if(allowed)keep.add(entryId);
            }
            parser.parseDocument(doc,path.toFile());ListContainer result=parser(parser).get(Integer.parseInt(name));
            check(result!=null&&result.isNpcAllowed(50010),"actual Native catalog owner parsed "+name);
            check(result.getEntries().stream().map(Entry::getEntryId).toList().equals(keep),"actual Native parser visibility and stable entry IDs "+name);
            for(Entry e:result.getEntries())
            {
                StringBuilder signature=new StringBuilder();
                for(Ingredient p:e.getProducts())signature.append("P").append(p.getItemId()).append(':').append(p.getItemCount()).append(';');
                for(Ingredient p:e.getIngredients())signature.append("I").append(p.getItemId()).append(':').append(p.getItemCount()).append(';');
                System.out.println("STABLE_ROW\t"+mode+"\t"+name+"\t"+e.getEntryId()+"\t"+signature);
                Element original=rows.get(e.getEntryId()-1);int n=0;
                for(Element in:children(original,"ingredient"))
                {
                    Ingredient actual=e.getIngredients().get(n++);
                    check(actual.getItemId()==Integer.parseInt(in.getAttribute("id"))&&actual.getItemCount()==Long.parseLong(in.getAttribute("count")),"actual price/currency untouched");
                }
                n=0;for(Element out:children(original,"production"))
                {
                    Ingredient actual=e.getProducts().get(n++);
                    check(actual.getItemId()==Integer.parseInt(out.getAttribute("id"))&&actual.getItemCount()==Long.parseLong(out.getAttribute("count")),"actual product count untouched");
                }
            }
        }
        BuyListData buy=blank(BuyListData.class);set(buy,BuyListData.class,"_buyLists",new HashMap<Integer,BuyListHolder>());
        Path path=data.resolve("buylists/0009931.xml");Document doc=xml(path);
        List<Integer> original=children(doc.getDocumentElement(),"item").stream().map(e->Integer.parseInt(e.getAttribute("id"))).toList();
        buy.parseDocument(doc,path.toFile());
        check(buy.getBuyList(9931).getProducts().stream().map(Product::getItemId).toList().equals(original.stream().filter(id->!GmShopEnchantPolicy.legacyBannedIds().contains(id)).toList()),"actual unbound buylist keeps original20 bans");
        doc=xml(path);
        Element owners=doc.createElement("npcs"),npc=doc.createElement("npc");npc.setTextContent("50010");owners.appendChild(npc);doc.getDocumentElement().appendChild(owners);
        buy.parseDocument(doc,path.toFile());
        check(buy.getBuyList(9931).getProducts().stream().map(Product::getItemId).toList().equals(original.stream().filter(id->!FORTUNA.contains(id)&&expected(id)).toList()),"actual BuyListData parses owners before filter");
        // Настоящий Native packet writer: число/ID/цена должны совпадать с отфильтрованным списком.
        BuyListHolder catalog=buyList(50010);BuyList packet=new BuyList(catalog,87654321L,0.15);
        WriteBuffer out=blank(WriteBuffer.class);set(out,WriteBuffer.class,"_buf",ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN));
        packet.writeImpl(null,out);out.limit(out.position());byte[] bytes=out.toByteArray();ByteBuffer wire=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        wire.position(3);check(wire.getLong()==87654321L&&wire.getInt()==catalog.getListId(),"packet money/list unchanged");
        List<Product> visible=GmShopEnchantPolicy.productsForViewer(catalog,null).stream().toList();
        check(Short.toUnsignedInt(wire.getShort())==visible.size(),"packet count equals visible rows");
        for(Product product:visible)
        {
            int start=wire.position();wire.position(start+6);check(wire.getInt()==product.getItemId(),"actual packet visible product ID");
            wire.position(start+32);check(wire.getLong()==(long)(123456*1.15),"actual packet taxed price preserved");wire.position(start+62);
        }
        check(wire.position()==bytes.length,"packet payload and filtered count consistent");
    }
    private static void legacy(boolean adapter) throws Exception
    {
        var filter=adapter?Class.forName("local.l2.SalePolicy").getMethod("filter",Document.class,File.class)
            :GmShopEnchantPolicy.class.getMethod("filterLegacyDocument",Document.class,File.class);
        if(adapter)check(Class.forName("local.l2.SalePolicy").getField("BANNED").get(null)==GmShopEnchantPolicy.legacyBannedIds(),"adapter uses same immutable Native table");
        Set<Integer> fortress=Set.of(22000,22001,22002,22003,22004,22005,20515,20516);
        Set<Integer> allBanned=new HashSet<>(fortress);allBanned.addAll(Set.of(20519,20520,20521,20522,22014,22015,22016,22017,22018,22019,22020,22021));
        for(String folder:new String[]{"multisell","buylists"})for(int owner:new int[]{0,30001,50010})
        {
            Document doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();Element root=doc.createElement("list");doc.appendChild(root);
            Element owners=doc.createElement("npcs");Element npc=doc.createElement("npc");npc.setTextContent(Integer.toString(owner));owners.appendChild(npc);root.appendChild(owners);
            for(int id:allBanned)
            {
                Element item=doc.createElement("item");item.setAttribute("id",Integer.toString(id));root.appendChild(item);
                if(folder.equals("multisell")){Element p=doc.createElement("production");p.setAttribute("id",Integer.toString(id));item.appendChild(p);}
            }
            filter.invoke(null,doc,new File("data/"+folder+"/123.xml"));
            Set<Integer> retained=new HashSet<>();for(Element e:children(root,"item"))retained.add(Integer.parseInt(e.getAttribute("id")));
            for(int id:allBanned)check(retained.contains(id)==(owner==50010&&!fortress.contains(id)),"DOM invariant8 GM/nonGM20 independent of settings "+id);
            check(((Integer)filter.invoke(null,doc,new File("data/"+folder+"/123.xml")))==0,"DOM filter second pass idempotent");
        }
    }
    public static void main(String[] args) throws Exception
    {
        try{DriverManager.getConnection("jdbc:gmshop-must-block:");throw new AssertionError("NoWorld agent missing");}
        catch(AssertionError e){if(!e.getMessage().startsWith("Blocked world/JDBC"))throw e;checks++;}
        mode=args[1];CommunityBoardConfig.COMMUNITY_SHOP_NPC=50010;
        Path data=Path.of(args[0]);items(data);scope();catalogs(data);
        legacy(false);
        if(args.length>2&&args[2].equals("legacy"))legacy(true);
        System.out.println("PASS gmshop-enchants "+mode+" checks="+checks+" NativeScrolls=60 supports=20 catalogs=3 world=false sql=false");
    }
}
