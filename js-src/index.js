import 'photoswipe/style.css';
import '../styles/main.scss';
import PhotoSwipeLightbox from 'photoswipe/lightbox';

const lightbox = new PhotoSwipeLightbox({
  gallery: '.pswp-gallery',
  children: 'a',
  pswpModule: () => import('photoswipe')
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
      const render = () => {
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
      pswp.on('change', render);
      render();
    },
  });
});

addEventListener('DOMContentLoaded', () => lightbox.init());
