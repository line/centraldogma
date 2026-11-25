import type { ClientModule } from '@docusaurus/types';

let legacyHash = '';

if (typeof window !== 'undefined') {
  const canonical = document.querySelector<HTMLLinkElement>(
    'link[rel="canonical"]',
  );
  if (canonical) {
    const canonicalPath = new URL(canonical.href).pathname;
    const htmlPath = canonicalPath.endsWith('/')
      ? `${canonicalPath}index.html`
      : `${canonicalPath}.html`;
    const { pathname, search, hash } = window.location;
    // Preload the canonical route before hydration can replace the fragment target.
    if (pathname === htmlPath) {
      legacyHash = hash;
      window.history.replaceState(
        window.history.state,
        '',
        `${canonicalPath}${search}${hash}`,
      );
    }
  }
}

export const onRouteDidUpdate: NonNullable<
  ClientModule['onRouteDidUpdate']
> = ({ location, previousLocation }) => {
  if (!previousLocation && legacyHash && location.hash === legacyHash) {
    let anchorId;
    try {
      anchorId = decodeURIComponent(legacyHash.slice(1));
    } catch {
      return;
    }
    document.getElementById(anchorId)?.scrollIntoView();
  }
};
