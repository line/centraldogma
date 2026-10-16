import { visit } from 'unist-util-visit';

export const createRemarkApiLink = (apiIndex) => () => (markdownAST) => {
  visit(markdownAST, 'link', (node) => {
    const typeInput = node.url;
    if (typeInput !== 'type' && typeInput !== 'typeplural') {
      return;
    }

    const typeNameInput = node.children[0].value;
    let optionIndex = typeNameInput.lastIndexOf('?');
    let option;
    if (optionIndex < 0) {
      optionIndex = typeNameInput.length;
    } else {
      option = typeNameInput.substring(optionIndex);
    }

    const typeName = decodeURIComponent(
      typeNameInput.substring(0, optionIndex),
    );
    const href = typeName.startsWith('@')
      ? apiIndex[typeName.substring(1)]
      : apiIndex[typeName];
    if (!href) {
      throw new Error(
        `Cannot find a unique Javadoc target for API link: ${typeName}. ` +
          'Use a fully qualified name.',
      );
    }

    node.children[0].value = typeName;
    node.url = `${typeInput}://${href}${option || ''}`;
  });
};
