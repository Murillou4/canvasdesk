package br.com.murillo.canvasdesk.core;

import com.google.gson.*;
import java.util.*;

/** Registry IDs and counts are document data; unavailable modded items remain recoverable. */
public record MaterialItem(String id,int quantidade) {
    public MaterialItem {
        if(id==null)throw new IllegalArgumentException("Item sem ID.");id=id.strip();if(!id.contains(":"))id="minecraft:"+id;
        if(id.equals("minecraft:chain"))id="minecraft:iron_chain";
        if(id.length()>256||!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))throw new IllegalArgumentException("ID de item inválido: "+id);
        if(quantidade<=0)throw new IllegalArgumentException("A quantidade deve ser um inteiro positivo.");
    }
    public static List<MaterialItem> normalize(Collection<MaterialItem> values){
        LinkedHashMap<String,Integer> merged=new LinkedHashMap<>();
        for(var item:values){try{merged.merge(item.id,item.quantidade,Math::addExact);}catch(ArithmeticException e){throw new IllegalArgumentException("Quantidade excede o limite para "+item.id);}}
        if(merged.size()>512)throw new IllegalArgumentException("Use até 512 itens por lista.");
        return merged.entrySet().stream().map(e->new MaterialItem(e.getKey(),e.getValue())).toList();
    }
    public static List<MaterialItem> parse(JsonArray array){
        if(array==null)return List.of();if(array.size()>512)throw new IllegalArgumentException("Use até 512 entradas por importação.");
        List<MaterialItem> values=new ArrayList<>();int row=0;
        for(JsonElement value:array){row++;try{
            JsonObject item=value.getAsJsonObject();JsonPrimitive quantity=item.getAsJsonPrimitive("quantidade");
            if(quantity==null||!quantity.isNumber())throw new IllegalArgumentException("Quantidade deve ser numérica.");
            values.add(new MaterialItem(item.get("id").getAsString(),quantity.getAsBigDecimal().intValueExact()));
        }catch(RuntimeException error){throw new IllegalArgumentException("Item "+row+": "+error.getMessage());}}
        return normalize(values);
    }
    public static JsonArray json(Collection<MaterialItem> values){JsonArray a=new JsonArray();for(var item:values){JsonObject o=new JsonObject();o.addProperty("id",item.id);o.addProperty("quantidade",item.quantidade);a.add(o);}return a;}
}
