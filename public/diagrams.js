if (window.mermaid && document.querySelector('.mermaid')) {
  mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: 'neutral', fontFamily: 'Pretendard, sans-serif', flowchart: { useMaxWidth: false }, sequence: { useMaxWidth: false } });
  mermaid.run({ querySelector: '.mermaid' }).then(() => {
    document.body.dataset.diagrams = 'ready';
  }).catch(() => {
    document.body.dataset.diagrams = 'error';
    const notice = document.createElement('p');
    notice.textContent = '그림을 표시하지 못했습니다. 페이지를 새로고침해 주세요.';
    document.querySelector('.article').prepend(notice);
  });
}
