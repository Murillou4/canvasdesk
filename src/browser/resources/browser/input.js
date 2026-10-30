(()=>{
 if(window.canvasInputInstalled)return;window.canvasInputInstalled=true;
 const report=(kind,extra)=>window.cefQuery&&cefQuery({request:JSON.stringify({kind,...extra}),onSuccess:()=>{},onFailure:()=>{}});
 const area=()=>{const el=document.activeElement;if(!el)return;const r=el.getBoundingClientRect();report('textArea',{x:Math.max(0,r.x),y:Math.max(0,r.y),w:Math.max(1,r.width),h:Math.max(16,r.height)});};
 addEventListener('focusin',area);addEventListener('selectionchange',area);
 const v=document.createElement('video');const mp4=v.canPlayType('video/mp4; codecs="avc1.42E01E, mp4a.40.2"');
 if(navigator.requestMediaKeySystemAccess)navigator.requestMediaKeySystemAccess('com.widevine.alpha',[{initDataTypes:['cenc'],videoCapabilities:[{contentType:'video/mp4; codecs="avc1.42E01E"'}]}]).then(()=>report('diagnostics',{widevine:true,mp4:!!mp4})).catch(()=>report('diagnostics',{widevine:false,mp4:!!mp4}));
 else report('diagnostics',{widevine:false,mp4:!!mp4});
})();
