import 'photoswipe/style.css';
import '../styles/main.scss';
import PhotoSwipeLightbox from 'photoswipe/lightbox';

const lightbox = new PhotoSwipeLightbox({
  gallery: '#my-gallery',
  children: 'a',
  pswpModule: () => import('photoswipe')
});


addEventListener("DOMContentLoaded", (event) => {
  lightbox.init();
  console.log("Photoswipe loaded");
});
