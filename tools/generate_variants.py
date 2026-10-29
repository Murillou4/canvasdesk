"""Models, shapes and recipes share editable art/geometry.json. No validation runs here."""
import copy
import json
from pathlib import Path
from PIL import Image

def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')

def models(root, export_png=None):
    assets=root/'src/main/resources/assets/canvasdesk'
    geometry=json.loads((root/'art/geometry.json').read_text(encoding='utf-8'))
    write(assets/'geometry.json',geometry)
    paper=Image.open(assets/'textures/block/desk_canvas.png').convert('RGBA')
    for color,rgb in geometry['colors'].items():
        image=Image.new('RGBA',paper.size)
        for y in range(paper.height):
            for x in range(paper.width):
                shade=paper.getpixel((x,y))[0]/250
                image.putpixel((x,y),tuple(min(255,round(((rgb>>s)&255)*shade)) for s in (16,8,0))+(255,))
        path=assets/f'textures/block/paper_{color}.png'
        if export_png: export_png(path,image)
        else: image.save(path)
    textures={'wood':'canvasdesk:block/desk_wood','end':'canvasdesk:block/desk_endgrain','copper':'canvasdesk:block/desk_copper','circuit':'canvasdesk:block/desk_circuit','particle':'canvasdesk:block/desk_wood','ink':'minecraft:block/black_concrete','eraser':'minecraft:block/gray_wool'}
    def point(p,mode):
        x,y,z=p
        if mode=='wall': return [x,y,z+6.5]
        if mode=='horizontal': return [x,9.5-z,y]
        return p
    def model(part,mode,color):
        elements=[]
        for box in part['boxes']:
            a,b=point(box['from'],mode),point(box['to'],mode)
            faces={f:{'texture':'#'+box['texture']} for f in ['down','up','north','south','west','east']}
            # Only the exposed paper face is visible. Side faces would be coplanar with the rim and flicker.
            if part['name']=='base' and box['texture']=='canvas':
                face='up' if mode=='horizontal' else 'north'
                faces={face:faces[face]}
            elements.append({'from':[min(a[i],b[i]) for i in range(3)],'to':[max(a[i],b[i]) for i in range(3)],'faces':faces})
        return {'ambientocclusion':False,'textures':{**textures,'canvas':f'canvasdesk:block/paper_{color}'},'elements':elements}
    for block,modes in [('desk',['vertical','wall']),('drawing_desk',['horizontal'])]:
        multipart=[]
        for mode in modes:
            for part in geometry[mode]:
                canvas=any(b['texture']=='canvas' for b in part['boxes'])
                colors=geometry['colors'] if canvas else ['white']
                for color in colors:
                    name=f'{block}_{mode}_{part["name"]}_{color}'
                    write(assets/f'models/block/{name}.json',model(part,mode,color))
                    for facing,rotation in [('north',0),('east',90),('south',180),('west',270)]:
                        when={'facing':facing,**part['when']}
                        if block=='desk':when['wall_mounted']=str(mode=='wall').lower()
                        if canvas:when['color']=color
                        multipart.append({'when':when,'apply':{'model':'canvasdesk:block/'+name,'y':rotation}})
        write(assets/f'blockstates/{block}.json',{'multipart':multipart})
        cases=[]
        for color in geometry['colors']:
            mode=modes[0]
            inventory={'parent':'minecraft:block/block','gui_light':'side','ambientocclusion':False,'textures':{**textures,'canvas':f'canvasdesk:block/paper_{color}'},'elements':[]}
            for part in geometry[mode]:inventory['elements'].extend(model(part,mode,color)['elements'])
            inventory['display']={'gui':{'rotation':[15,150,0],'scale':[.8,.8,.8]},'ground':{'translation':[0,3,0],'scale':[.3,.3,.3]},'fixed':{'rotation':[0,180,0],'scale':[.75,.75,.75]},'thirdperson_righthand':{'rotation':[0,90,0],'scale':[.45,.45,.45]},'firstperson_righthand':{'rotation':[0,140,0],'scale':[.55,.55,.55]},'firstperson_lefthand':{'rotation':[0,220,0],'scale':[.55,.55,.55]}}
            write(assets/f'models/item/{block}_{color}.json',inventory)
            cases.append({'when':color,'model':{'type':'minecraft:model','model':f'canvasdesk:item/{block}_{color}'}})
        write(assets/f'items/{block}.json',{'model':{'type':'minecraft:select','property':'minecraft:block_state','block_state_property':'color','cases':cases,'fallback':{'type':'minecraft:model','model':f'canvasdesk:item/{block}_white'}}})

def data_files(root):
    data=root/'src/main/resources/data'
    colors=json.loads((root/'art/geometry.json').read_text(encoding='utf-8'))['colors']
    recipes=[]
    for block,pattern in [('desk',['PPP','CRC','WWW']),('drawing_desk',['PPP','WWW','CRC'])]:
        recipes.append(block)
        write(data/f'canvasdesk/recipe/{block}.json',{'type':'minecraft:crafting_shaped','category':'building','group':'canvasdesk','pattern':pattern,'key':{'P':'minecraft:paper','C':'minecraft:copper_ingot','R':'minecraft:redstone','W':'#minecraft:planks'},'result':{'id':'canvasdesk:'+block,'count':1}})
        write(data/f'canvasdesk/loot_table/blocks/{block}.json',{'type':'minecraft:block','pools':[{'rolls':1,'condition':{'type':'minecraft:survives_explosion'},'entries':[{'type':'minecraft:item','name':'canvasdesk:'+block,'modifier':[{'type':'minecraft:copy_components','source':'block_entity','include':['minecraft:custom_data']},{'type':'minecraft:copy_state','block':'canvasdesk:'+block,'properties':['color']}]}]}],'random_sequence':'canvasdesk:blocks/'+block})
        for color in colors:
            name=f'{block}_{color}';recipes.append(name)
            write(data/f'canvasdesk/recipe/{name}.json',{'type':'minecraft:crafting_transmute','group':'canvasdesk_dye','input':'canvasdesk:'+block,'material':f'minecraft:{color}_dye','result':{'id':'canvasdesk:'+block,'components':{'minecraft:block_state':{'color':color}}}})
        target='drawing_desk' if block=='desk' else 'desk'
        name=f'{block}_to_{target}';recipes.append(name)
        write(data/f'canvasdesk/recipe/{name}.json',{'type':'minecraft:crafting_transmute','group':'canvasdesk_format','input':'canvasdesk:'+block,'material':'minecraft:stick','result':{'id':'canvasdesk:'+target}})
    write(data/'minecraft/tags/block/mineable/axe.json',{'replace':False,'values':['canvasdesk:desk','canvasdesk:drawing_desk']})
    for name in recipes:
        write(data/f'canvasdesk/advancement/recipes/decorations/{name}.json',{'parent':'minecraft:recipes/root','criteria':{'has_paper':{'trigger':'minecraft:inventory_changed','conditions':{'items':[{'items':'minecraft:paper'}]}},'has_the_recipe':{'trigger':'minecraft:recipe_unlocked','conditions':{'recipes':'canvasdesk:'+name}}},'requirements':[['has_paper','has_the_recipe']],'rewards':{'recipes':['canvasdesk:'+name]}})
