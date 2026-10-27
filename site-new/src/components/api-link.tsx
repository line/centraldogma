import React from 'react';
import styles from './api-link.module.css';
import { simpleApiName } from './api-link-name';

interface ApiLinkProps {
  name: string;
  href?: string;
  plural?: boolean;
}

const ApiLink: React.FC<ApiLinkProps> = (props) => {
  let simpleName = simpleApiName(decodeURIComponent(props.name));

  let showParams = false;
  let href = props.href;
  if (href) {
    const optionIndex = href.lastIndexOf('?');
    if (optionIndex > 0) {
      showParams = href.substring(optionIndex + 1) === 'full';
      href = href.substring(0, optionIndex);
    }
  }

  let suffix = '';
  if (props.plural) {
    suffix = simpleName.match(/(ch|s|sh|x|z)$/) ? 'es' : 's';
  }

  let title = '';
  if (simpleName.indexOf('#') > 0) {
    const replaced = simpleName.replace('#', '.');
    title = replaced;
    simpleName = showParams ? replaced : replaced.replace(/ *\([^)]*\)*/, '()');
  } else {
    title = simpleName;
  }

  return (
    <code>
      <a href={href} title={title}>
        {simpleName}
        <span className={styles.suffix}>{suffix}</span>
      </a>
    </code>
  );
};

export default ApiLink;
