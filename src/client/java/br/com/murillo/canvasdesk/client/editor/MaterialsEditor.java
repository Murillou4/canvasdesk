package br.com.murillo.canvasdesk.client.editor;

import br.com.murillo.canvasdesk.core.MaterialItem;

import com.google.gson.*;

import java.util.*;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import net.minecraft.client.gui.components.*;

import net.minecraft.client.gui.narration.NarrationElementOutput;

import net.minecraft.client.input.MouseButtonEvent;

import net.minecraft.core.registries.BuiltInRegistries;

import net.minecraft.network.chat.Component;

import net.minecraft.world.item.*;

/** A draft editor: imports/visual edits only become one atomic document operation on Save. */

final class MaterialsEditor {

    private final JsonObject original;

    private final List<MaterialItem> values=new ArrayList<>();

    private final List<ItemStack> catalogue=new ArrayList<>(),filtered=new ArrayList<>();

    private String title,search="",json="",message="";private boolean jsonMode,invalidQuantity,compact;

    private int selected=-1,offset,searchOffset,x,y,w,h;

    private Consumer<AbstractWidget> add;private Runnable rebuild;

    private EditBox quantity;private AbstractWidget initial;
    AbstractWidget initialFocus(){return initial;}

    MaterialsEditor(JsonObject original){this.original=original.deepCopy();title=CanvasElementView.string(original,"text","");values.addAll(MaterialItem.parse(original.getAsJsonArray("items")));

        for(Item item:BuiltInRegistries.ITEM)if(item!=Items.AIR)catalogue.add(new ItemStack(item));catalogue.sort(Comparator.comparing(s->s.getHoverName().getString()));filtered.addAll(catalogue);

    }

    AbstractWidget build(int screenW,int screenH,Consumer<AbstractWidget> add,Runnable rebuild,Consumer<JsonObject> save,Runnable close){

        this.add=add;this.rebuild=rebuild;x=(screenW-(w=Math.min(560,screenW-20)))/2;y=(screenH-(h=Math.min(390,screenH-16)))/2;compact=w<380;

        var font=Minecraft.getInstance().font;

        var titleField=new EditBox(font,x+12,y+28,w-24,20,Component.literal("Título da construção"));titleField.setMaxLength(256);titleField.setValue(title);titleField.setResponder(v->title=v);add.accept(titleField);initial=titleField;

        button(x+12,y+h-28,68,"Cancelar",close);button(x+w-82,y+h-28,70,"Salvar",()->{

            try{if(jsonMode)parse();else if(invalidQuantity)throw new IllegalArgumentException("Quantidade inteira positiva.");if(values.isEmpty())throw new IllegalArgumentException("Adicione pelo menos um item.");

                JsonObject after=original.deepCopy();after.addProperty("text",title);after.add("items",MaterialItem.json(values));after.addProperty("h",MaterialsLayout.of(font,new CanvasElementView(after)).height());save.accept(after);

            }catch(RuntimeException error){message=error.getMessage();}

        });

        button(x+86,y+h-28,compact?48:70,jsonMode?"Visual":"JSON",()->{if(jsonMode){try{parse();jsonMode=false;}catch(RuntimeException e){message=e.getMessage();return;}}else{json=new GsonBuilder().setPrettyPrinting().create().toJson(MaterialItem.json(values));jsonMode=true;}rebuild.run();});

        button(x+(compact?140:162),y+h-28,compact?60:70,"Exportar",()->{Minecraft.getInstance().keyboardHandler.setClipboard(new GsonBuilder().setPrettyPrinting().create().toJson(MaterialItem.json(values)));message="JSON copiado.";});

        if(jsonMode){

            var field=MultiLineEditBox.builder().setX(x+12).setY(y+57).setShowBackground(true).build(font,w-24,h-(compact?136:112),Component.literal("JSON dos materiais"));field.setCharacterLimit(262144);field.setValue(json);field.setValueListener(v->json=v);add.accept(field);initial=field;

            button(x+(compact?12:238),y+h-(compact?52:28),76,"Importar",()->{try{parse();message="Importado: "+values.size()+" itens. "+message;}catch(RuntimeException e){message=e.getMessage();}});

        }else{

            int half=w/2;var find=new EditBox(font,x+half+6,y+57,w-half-18,20,Component.literal("Buscar item"));find.setHint(Component.literal("Buscar item / ID"));find.setValue(search);find.setResponder(v->{search=v;filter();});add.accept(find);

            int rows=Math.max(1,(h-157)/26),columns=Math.max(1,(half-20)/28);

            for(int i=0;i<rows*columns;i++){final int slot=i;add.accept(new ItemButton(x+12+(i%columns)*28,y+58+(i/columns)*26,()->{int n=offset+slot;return n<values.size()?MaterialIcons.stack(values.get(n).id()):ItemStack.EMPTY;},()->{selected=offset+slot;invalidQuantity=false;rebuild.run();},()->selected==offset+slot,()->offset+slot<values.size()?Integer.toString(values.get(offset+slot).quantidade()):""));}

            int pickCols=Math.max(1,(w-half-20)/28),pickRows=Math.max(1,(h-136)/26);

            for(int i=0;i<pickCols*pickRows;i++){final int slot=i;add.accept(new ItemButton(x+half+6+(i%pickCols)*28,y+84+(i/pickCols)*26,()->searchOffset+slot<filtered.size()?filtered.get(searchOffset+slot):ItemStack.EMPTY,()->{int n=searchOffset+slot;if(n>=filtered.size())return;String id=BuiltInRegistries.ITEM.getKey(filtered.get(n).getItem()).toString();int existing=-1;for(int j=0;j<values.size();j++)if(values.get(j).id().equals(id))existing=j;

                if(existing>=0){var old=values.get(existing);if(old.quantidade()==Integer.MAX_VALUE){message="Quantidade máxima atingida.";return;}values.set(existing,new MaterialItem(id,old.quantidade()+1));selected=existing;}

                else{if(values.size()>=512){message="Limite de 512 itens.";return;}values.add(new MaterialItem(id,1));selected=values.size()-1;offset=Math.max(0,(selected/(columns))*columns-(rows-1)*columns);}

                rebuild.run();},()->false));}

            quantity=new EditBox(font,x+12,y+h-78,Math.max(55,half-102),20,Component.literal("Quantidade"));quantity.setMaxLength(10);quantity.setValue(selected>=0&&selected<values.size()?Integer.toString(values.get(selected).quantidade()):"");quantity.setResponder(v->{if(selected>=0&&selected<values.size())try{int q=Integer.parseInt(v);if(q<=0)throw new NumberFormatException();values.set(selected,new MaterialItem(values.get(selected).id(),q));invalidQuantity=false;message="";}catch(NumberFormatException e){invalidQuantity=true;message="Quantidade inteira positiva.";}});add.accept(quantity);

            button(x+half-83,y+h-78,24,"↑",()->move(-1));button(x+half-55,y+h-78,24,"↓",()->move(1));button(x+half-27,y+h-78,24,"×",()->{if(selected>=0&&selected<values.size()){values.remove(selected);selected=Math.min(selected,values.size()-1);rebuild.run();}});

            button(x+12,y+h-54,24,"‹",()->{offset=Math.max(0,offset-rows*columns);rebuild.run();});button(x+40,y+h-54,24,"›",()->{offset=Math.max(0,Math.min(Math.max(0,values.size()-1),offset+rows*columns));rebuild.run();});

            button(x+half+6,y+h-54,24,"‹",()->{searchOffset=Math.max(0,searchOffset-pickCols*pickRows);});button(x+half+34,y+h-54,24,"›",()->{searchOffset=Math.max(0,Math.min(Math.max(0,filtered.size()-1),searchOffset+pickCols*pickRows));});

        }return initial;

    }

    private void filter(){String q=search.toLowerCase(Locale.ROOT);filtered.clear();for(var stack:catalogue)if(stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)||BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().contains(q))filtered.add(stack);searchOffset=0;}

    private void parse(){boolean alias=json.contains("minecraft:chain\"");var parsed=MaterialItem.parse(JsonParser.parseString(json).getAsJsonArray());values.clear();values.addAll(parsed);selected=-1;offset=0;message=alias?"chain convertido para iron_chain. ":"";long missing=values.stream().filter(i->MaterialIcons.missing(i.id())).count();if(missing>0)message+=missing+" ID(s) indisponível(is); dados preservados.";}

    private void move(int delta){int target=selected+delta;if(selected>=0&&target>=0&&target<values.size()){Collections.swap(values,selected,target);selected=target;rebuild.run();}}

    private void button(int x,int y,int width,String label,Runnable run){add.accept(new CanvasButton(x,y,Math.max(20,width),20,label,null,run,()->false,()->true,null));}

    void paint(GuiGraphicsExtractor g,int mouseX,int mouseY){var font=Minecraft.getInstance().font;g.fill(0,0,g.guiWidth(),g.guiHeight(),0x990f100e);g.fill(x,y,x+w,y+h,0xfffafaf7);g.outline(x,y,w,h,0xff967a57);g.text(font,"Materiais da construção",x+12,y+10,0xff34342e,false);g.text(font,font.plainSubstrByWidth(message,w-24),x+12,y+h-(compact&&jsonMode?64:40),0xffa46e48,false);

        if(!jsonMode)g.text(font,"Quantidade",x+12,y+h-89,0xff827b6d,false);

    }

    private static final class ItemButton extends AbstractWidget {

        private final java.util.function.Supplier<ItemStack> stack;private final Runnable click;private final java.util.function.BooleanSupplier selected;private final java.util.function.Supplier<String> count;

        ItemButton(int x,int y,java.util.function.Supplier<ItemStack> stack,Runnable click,java.util.function.BooleanSupplier selected){this(x,y,stack,click,selected,()->"");}

        ItemButton(int x,int y,java.util.function.Supplier<ItemStack> stack,Runnable click,java.util.function.BooleanSupplier selected,java.util.function.Supplier<String> count){super(x,y,24,24,Component.literal("Item"));this.stack=stack;this.click=click;this.selected=selected;this.count=count;}

        protected void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){var s=stack.get();active=!s.isEmpty();g.fill(getX(),getY(),getRight(),getBottom(),selected.getAsBoolean()?0xffd8c1a2:0xfff0ece1);if(!s.isEmpty()){g.item(s,getX()+4,getY()+1);String q=count.get();var font=Minecraft.getInstance().font;if(!q.isEmpty()){float scale=Math.min(1,24f/Math.max(1,font.width(q)));g.pose().pushMatrix();g.pose().translate(getX()+12,getY()+16);g.pose().scale(scale,scale);g.text(font,q,-font.width(q)/2,0,0xff34342e,false);g.pose().popMatrix();}if(isHoveredOrFocused())g.setTooltipForNextFrame(font,s,mx,my);}}

        public void onClick(MouseButtonEvent e,boolean doubleClick){if(!stack.get().isEmpty())click.run();}

        protected void updateWidgetNarration(NarrationElementOutput output){defaultButtonNarrationText(output);}

    }

}

