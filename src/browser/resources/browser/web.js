(()=>{if(window.canvasInstalled)return;window.canvasInstalled=true;let desired={},ignoreUntil=0,lastScroll=0,revision=-1;
 function report(kind,extra){if(window.cefQuery)cefQuery({request:JSON.stringify({kind,...extra}),onSuccess:()=>{},onFailure:()=>{}})}
 window.canvasApply=s=>{desired=s;const el=document.scrollingElement;if(el&&(s.mediaSession+"/"+s.mediaRevision)!==revision){revision=s.mediaSession+"/"+s.mediaRevision;const x=(el.scrollWidth-innerWidth)*(s.scrollX||0),y=(el.scrollHeight-innerHeight)*(s.scrollY||0);if(Math.abs(scrollX-x)>2||Math.abs(scrollY-y)>2){ignoreUntil=Date.now()+150;scrollTo(x,y);}}for(const m of document.querySelectorAll('audio,video'))m.volume=Math.max(0,Math.min(1,s.volume??0));};
 addEventListener('scroll',()=>{if(!desired.editable||Date.now()<ignoreUntil||Date.now()-lastScroll<100)return;lastScroll=Date.now();let e=document.scrollingElement;report('scroll',{scrollX:scrollX/Math.max(1,e.scrollWidth-innerWidth),scrollY:scrollY/Math.max(1,e.scrollHeight-innerHeight)});},{passive:true});
 addEventListener('submit',()=>report('privateForm',{}),true);
})();
