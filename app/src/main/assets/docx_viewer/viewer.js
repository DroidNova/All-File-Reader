'use strict';
const host = document.getElementById('document');
const state = { status: 'loading', page: 0, pages: 0, error: '' };
const session = new URLSearchParams(location.search).get('session');
function reportPage() {
  if (state.status !== 'ready') return;
  const pages = [...host.querySelectorAll('.docx-wrapper > section.docx')];
  if (!pages.length) { state.page = 0; state.pages = 0; return; }
  const y = innerHeight * .35;
  let selected = pages.findIndex(p => { const r=p.getBoundingClientRect(); return r.top <= y && r.bottom >= y; });
  if (selected < 0) selected = pages.findIndex(p => p.getBoundingClientRect().bottom > 0);
  state.page = Math.max(1, selected + 1); state.pages = pages.length;
}
window.viewerState = () => JSON.stringify(state);
window.fitWidth = () => {
  const page = host.querySelector('section.docx'); if (!page) return;
  host.style.zoom = Math.min(1, Math.max(.1, (innerWidth - 16) / page.offsetWidth));
  scrollTo(0, 0); reportPage();
};
addEventListener('scroll', reportPage, {passive:true});
addEventListener('resize', reportPage, {passive:true});
(async()=>{try{
  if(!session || !/^[a-f0-9]{48}$/.test(session)) throw Error('invalid-session');
  const response=await fetch(`/session/${session}/document.docx`,{cache:'no-store',credentials:'omit'});
  if(!response.ok) throw Error('document-unavailable');
  const data=await response.arrayBuffer();
  await Promise.race([
    docx.renderAsync(data,host,null,{renderAltChunks:false,breakPages:true,ignoreLastRenderedPageBreak:false}),
    new Promise((_,reject)=>setTimeout(()=>reject(Error('timeout')),30000))
  ]);
  state.status='ready'; fitWidth(); reportPage();
}catch(e){state.status='error';state.error=e && e.message==='timeout'?'timeout':'render';host.textContent='This Word document could not be rendered.'}
})();
