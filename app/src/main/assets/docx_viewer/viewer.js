'use strict';
const host = document.getElementById('document');
let matches = [], current = -1, scale = 1;
const session = new URLSearchParams(location.search).get('session');
const reportSearch = () => AndroidViewer.searchState(matches.length ? current + 1 : 0, matches.length);
function reportPage() {
  const pages = [...host.querySelectorAll('.docx-wrapper > section.docx')];
  if (!pages.length) return AndroidViewer.pageState(0, 0);
  const y = innerHeight * .35;
  let selected = pages.findIndex(p => { const r=p.getBoundingClientRect(); return r.top <= y && r.bottom >= y; });
  if (selected < 0) selected = pages.findIndex(p => p.getBoundingClientRect().bottom > 0);
  AndroidViewer.pageState(Math.max(1, selected + 1), pages.length);
}
function clearMarks() {
  host.querySelectorAll('mark.docx-search').forEach(mark => mark.replaceWith(document.createTextNode(mark.textContent)));
  host.normalize(); matches=[]; current=-1; reportSearch();
}
function select(index) {
  if (!matches.length) return reportSearch();
  matches.forEach(m => m.classList.remove('active'));
  current=(index+matches.length)%matches.length; matches[current].classList.add('active');
  matches[current].scrollIntoView({block:'center',behavior:'smooth'}); reportSearch();
}
window.viewer = {
  clearSearch: clearMarks,
  search(query) {
    clearMarks(); const needle=query.trim().toLocaleLowerCase(); if (!needle) return;
    const walker=document.createTreeWalker(host,NodeFilter.SHOW_TEXT,{acceptNode:n=>n.parentElement.closest('mark,script,style')?NodeFilter.FILTER_REJECT:NodeFilter.FILTER_ACCEPT});
    const nodes=[]; while(walker.nextNode()) nodes.push(walker.currentNode);
    nodes.forEach(node => { const text=node.data, lower=text.toLocaleLowerCase(); let from=0, index; const fragment=document.createDocumentFragment();
      while((index=lower.indexOf(needle,from))>=0){fragment.append(text.slice(from,index));const mark=document.createElement('mark');mark.className='docx-search';mark.textContent=text.slice(index,index+needle.length);fragment.append(mark);matches.push(mark);from=index+needle.length}
      if(from){fragment.append(text.slice(from));node.replaceWith(fragment)}
    }); select(0);
  },
  next(){select(current+1)}, previous(){select(current-1)},
  fitWidth(){ const page=host.querySelector('section.docx'); if(!page)return; scale=Math.min(1,(innerWidth-16)/page.offsetWidth);host.style.zoom=scale;scrollTo(0,0);reportPage(); }
};
addEventListener('scroll',reportPage,{passive:true}); addEventListener('resize',reportPage,{passive:true});
(async()=>{try{if(!session||!/^[a-f0-9]{48}$/.test(session))throw Error('Invalid session');const response=await fetch(`/session/${session}/document.docx`,{cache:'no-store',credentials:'omit'});if(!response.ok)throw Error('Document unavailable');const data=await response.arrayBuffer();await docx.renderAsync(data,host,null,{renderAltChunks:false,breakPages:true,ignoreLastRenderedPageBreak:false});viewer.fitWidth();reportPage()}catch(e){host.textContent='This Word document could not be rendered.';AndroidViewer.pageState(0,0)}})();
