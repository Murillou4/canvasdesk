package br.com.murillo.canvasdesk.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MediaAndMaterialsTest {
    @TempDir Path directory;
    @Test void importsTheProvided29ItemsAndAlias() throws Exception {
        try(var input=getClass().getResourceAsStream("/materials-29.json")){
            var items=MaterialItem.parse(JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray());
            assertEquals(29,items.size());assertEquals(new MaterialItem("minecraft:cherry_planks",448),items.getFirst());assertEquals(new MaterialItem("minecraft:iron_chain",12),items.get(27));assertEquals(768,items.get(24).quantidade());assertEquals(items,MaterialItem.parse(MaterialItem.json(items)));
        }
    }
    @Test void duplicatesKeepFirstPositionAndSumExactly(){
        var result=MaterialItem.parse(JsonParser.parseString("[{\"id\":\"chain\",\"quantidade\":12},{\"id\":\"stone\",\"quantidade\":3},{\"id\":\"minecraft:iron_chain\",\"quantidade\":5}]").getAsJsonArray());
        assertEquals(List.of(new MaterialItem("iron_chain",17),new MaterialItem("stone",3)),result);
    }
    @Test void rejectsInvalidQuantitiesAndOverflowWithoutPartialResult(){
        for(String q:List.of("0","-1","1.2","2147483648","\"12\"","null"))assertThrows(IllegalArgumentException.class,()->MaterialItem.parse(JsonParser.parseString("[{\"id\":\"stone\",\"quantidade\":"+q+"}]").getAsJsonArray()));
        assertThrows(IllegalArgumentException.class,()->MaterialItem.normalize(List.of(new MaterialItem("stone",Integer.MAX_VALUE),new MaterialItem("stone",1))));
        assertThrows(IllegalArgumentException.class,()->new MaterialItem("bad ID",1));
        var items=new ArrayList<MaterialItem>();for(int i=0;i<512;i++)items.add(new MaterialItem("missing:item_"+i,1));assertEquals(512,MaterialItem.normalize(items).size());items.add(new MaterialItem("missing:overflow",1));assertThrows(IllegalArgumentException.class,()->MaterialItem.normalize(items));
    }
    @Test void isolatedHttpUrlsOnlyAndProviderClassification(){
        assertEquals("",EmbedSource.youtubeId("https://youtu.be"));
        assertTrue(EmbedSource.isolated("https://example.org/path?q=a"));assertFalse(EmbedSource.isolated("Visite https://example.org"));assertFalse(EmbedSource.isolated("file:///C:/secret"));
        assertEquals("dQw4w9WgXcQ",EmbedSource.youtubeId("https://youtu.be/dQw4w9WgXcQ"));assertEquals("dQw4w9WgXcQ",EmbedSource.youtubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));assertTrue(EmbedSource.directVideo("http://localhost/movie.webm?q=1"));
        assertThrows(IllegalArgumentException.class,()->EmbedSource.validate("https://user:pass@example.org/"));
    }
    @Test void importsAreBoundedBeforeMergingDuplicates(){var rows=new JsonArray();for(int i=0;i<513;i++)rows.add(json("{id:stone,quantidade:1}"));assertThrows(IllegalArgumentException.class,()->MaterialItem.parse(rows));}
    @Test void mediaSessionsHaveIndependentIdentities(){var a=new MediaTimeline("https://example.org",0);var b=new MediaTimeline(a.source,0);assertNotEquals(a.session,b.session);assertEquals(a.session,a.snapshot(5).get("mediaSession").getAsString());}
    @Test void newFieldsSurviveDocumentUnionAndReopen()throws Exception{UUID owner=UUID.randomUUID(),target,source;var embed=element("EMBED");var materials=element("MATERIALS");try(var store=new CanvasStore(directory)){target=store.create(owner);source=store.create(owner);assertTrue(store.action(source,owner,edit(-1,embed,materials)).get("ok").getAsBoolean());assertTrue(store.requestMerge(target,source,256,-256,owner).get("merged").getAsBoolean());assertEquals(target,store.resolve(source));}try(var store=new CanvasStore(directory)){var e=store.element(target,UUID.fromString(embed.get("id").getAsString()));assertEquals(276,e.get("x").getAsDouble());assertEquals("https://example.org",e.get("url").getAsString());var m=store.element(target,UUID.fromString(materials.get("id").getAsString()));assertEquals(12,m.getAsJsonArray("items").get(0).getAsJsonObject().get("quantidade").getAsInt());}}
    @Test void mediaClockLateJoinPauseSeekAndRevisionConflict(){
        var timeline=new MediaTimeline("https://example.org/video.webm",1000);assertFalse(timeline.snapshot(1000).get("playing").getAsBoolean());
        timeline.command("play",json("{position:2}"),0,1000);assertEquals(5,timeline.position(4000),.0001);
        assertThrows(IllegalArgumentException.class,()->timeline.command("pause",json("{}"),0,4000));assertEquals(1,timeline.revision());
        timeline.command("pause",json("{}"),1,4000);assertEquals(5,timeline.position(10000),.0001);timeline.command("seek",json("{position:20}"),2,10000);assertEquals(20,timeline.position(11000));
        var restart=new MediaTimeline(timeline.source,12000);assertEquals(0,restart.position(13000));assertFalse(restart.snapshot(13000).get("playing").getAsBoolean());
    }
    @Test void worldToggleUsesServerPositionWithoutSeekingOrPersisting(){
        var t=new MediaTimeline("https://example.org/video.webm",1000);
        t.command("toggle",json("{position:999}"),0,2000);
        assertTrue(t.snapshot(2000).get("playing").getAsBoolean());assertEquals(3,t.position(5000));
        t.command("toggle",json("{position:0}"),1,5000);
        assertFalse(t.snapshot(9000).get("playing").getAsBoolean());assertEquals(3,t.position(9000));
        t.command("toggle",json("{}"),2,10000);assertEquals(5,t.position(12000));assertEquals(3,t.revision());
    }
    @Test void staleWorldToggleDoesNotInvertTheNewerPlaybackState(){
        var t=new MediaTimeline("https://example.org/video.webm",0);t.command("toggle",json("{}"),0,0);
        assertThrows(IllegalArgumentException.class,()->t.command("toggle",json("{}"),0,1000));
        assertTrue(t.snapshot(1000).get("playing").getAsBoolean());assertEquals(1,t.revision());
    }
    @Test void worldVideoHitExcludesLetterboxingAndOutsideCoordinates(){
        var r=EmbedGeometry.fit(20,20,200,200);
        assertTrue(r.contains(120,120));assertFalse(r.contains(120,21));assertFalse(r.contains(120,219));
        assertFalse(r.contains(220,120));assertFalse(r.contains(Double.NaN,120));
    }
    @Test void invalidMediaCommandsAreAtomicAndNavigationIsTransient(){
        var t=new MediaTimeline("https://example.org",0);t.command("scroll",json("{scrollX:0.2,scrollY:0.3}"),0,0);
        assertThrows(IllegalArgumentException.class,()->t.command("scroll",json("{scrollX:0.8,scrollY:2}"),1,5));assertEquals(.2,t.snapshot(5).get("scrollX").getAsDouble());assertEquals(1,t.revision());
        t.command("navigate",json("{url:'https://example.org/next'}"),1,10);assertEquals("https://example.org/next",t.snapshot(10).get("navigationUrl").getAsString());assertEquals(0,t.snapshot(10).get("scrollY").getAsDouble());
    }
    @Test void oldElementsDefaultEmptyAndNewFieldsSurviveTransforms(){
        var old=json("{id:'"+UUID.randomUUID()+"',type:'TEXT',text:'ação'}");var e=CanvasElement.fromJson(old);assertEquals("",e.url());assertTrue(e.items().isEmpty());
        for(String type:List.of("EMBED","MATERIALS")){var value=element(type);var transformed=CanvasElement.fromJson(value).translated(256,-256,UUID.randomUUID()).withRevision(9);var roundTrip=CanvasElement.fromJson(transformed.toJson());assertEquals(transformed,roundTrip);assertEquals(type.equals("EMBED")?"https://example.org":"",roundTrip.url());assertEquals(type.equals("MATERIALS")?1:0,roundTrip.items().size());}
    }
    @Test void documentAtomicHistoryPermissionsConflictsDedupAndPersistence() throws Exception {
        UUID owner=UUID.randomUUID(),reader=UUID.randomUUID(),doc;var embed=element("EMBED");var materials=element("MATERIALS");
        try(var store=new CanvasStore(directory)){
            doc=store.create(owner);var op=edit(-1,embed,materials);assertTrue(store.action(doc,owner,op).get("ok").getAsBoolean());assertTrue(store.action(doc,owner,op).get("ok").getAsBoolean());assertEquals(2,store.elementCount(doc));
            assertFalse(store.action(doc,reader,edit(0,embed)).get("ok").getAsBoolean());assertFalse(store.action(doc,owner,edit(-1,embed)).get("ok").getAsBoolean());
            assertTrue(store.action(doc,owner,json("{action:undo}")).get("ok").getAsBoolean());assertEquals(0,store.elementCount(doc));assertTrue(store.action(doc,owner,json("{action:redo}")).get("ok").getAsBoolean());assertEquals(2,store.elementCount(doc));
        }
        try(var store=new CanvasStore(directory)){assertEquals("https://example.org",store.element(doc,UUID.fromString(embed.get("id").getAsString())).get("url").getAsString());assertEquals("minecraft:iron_chain",store.element(doc,UUID.fromString(materials.get("id").getAsString())).getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString());}
    }
    private static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    private static JsonObject element(String type){return json("{id:'"+UUID.randomUUID()+"',type:'"+type+"',x:20,y:30,w:200,h:100,text:'Título',url:'https://example.org',items:[{id:'minecraft:chain',quantidade:12}]}");}
    private static JsonObject edit(long revision,JsonObject... elements){var r=json("{action:edit,operationId:'"+UUID.randomUUID()+"'}");var changes=new JsonArray();for(var e:elements){var c=new JsonObject();c.add("id",e.get("id"));c.addProperty("expectedRevision",revision);c.add("after",e);changes.add(c);}r.add("changes",changes);return r;}
}
