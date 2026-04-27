import 'photoswipe/style.css';
import '../styles/main.scss';
import PhotoSwipeLightbox from 'photoswipe/lightbox';

const FOOTER_HEIGHT = 30;

const lightbox = new PhotoSwipeLightbox({
  gallery: '.pswp-gallery',
  children: 'a',
  pswpModule: () => import('photoswipe'),
  paddingFn: () => ({ top: 0, bottom: FOOTER_HEIGHT + 8, left: 0, right: 0 }),
});

const esc = s => (s ?? '').replace(/[&<>"']/g, c =>
  ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

lightbox.on('uiRegister', () => {
  lightbox.pswp.ui.registerElement({
    name: 'galerie-footer',
    order: 9,
    isButton: false,
    appendTo: 'wrapper',
    html: '',
    onInit: (el, pswp) => {
      const renderContent = () => {
        const a = pswp.currSlide && pswp.currSlide.data && pswp.currSlide.data.element;
        if (!a) { el.innerHTML = ''; return; }
        const d = a.dataset;
        const fotoBlock = d.fotografName
          ? `<div>${esc(d.fotoLabel)}: ${
              d.fotografLink
                ? `<a class="standardlink" target="_blank" rel="noopener noreferrer" href="${esc(d.fotografLink)}">${esc(d.fotografName)}</a>`
                : esc(d.fotografName)
            }</div>`
          : '<div></div>';
        const fullLink = `<a class="standardlink" target="_blank" rel="noopener noreferrer" href="${esc(d.fullUrl)}">→ ${esc(d.vollaufloesungLabel)}</a>`;
        el.innerHTML = fotoBlock + fullLink;
      };
      const positionToImage = () => {
        const slide = pswp.currSlide;
        if (!slide) return;
        const zoom = slide.currZoomLevel || slide.zoomLevels.initial || 1;
        const imgW = slide.width * zoom;
        const imgH = slide.height * zoom;
        const area = slide.panAreaSize || pswp.viewportSize;
        const left = (area.x - imgW) / 2 + (slide.pan ? slide.pan.x - (area.x - imgW) / 2 : 0);
        const top  = (area.y - imgH) / 2 + (slide.pan ? slide.pan.y - (area.y - imgH) / 2 : 0) + imgH;
        el.style.left  = Math.max(0, left) + 'px';
        el.style.width = Math.min(area.x, imgW) + 'px';
        el.style.top   = Math.min(area.y, top) + 'px';
      };
      pswp.on('change', () => { renderContent(); positionToImage(); });
      pswp.on('zoomPanUpdate', positionToImage);
      pswp.on('resize', positionToImage);
      renderContent();
      positionToImage();
    },
  });
});

addEventListener('DOMContentLoaded', () => lightbox.init());
