const simpleTypeName = (typeName) => {
  const varargs = typeName.endsWith('...');
  const normalized = varargs
    ? typeName.substring(0, typeName.length - 3)
    : typeName;
  const lastDotIdx = normalized.lastIndexOf('.');
  const simpleName =
    lastDotIdx >= 0 ? normalized.substring(lastDotIdx + 1) : normalized;
  return varargs ? `${simpleName}...` : simpleName;
};

const simpleApiName = (name) => {
  const annotation = name.startsWith('@');
  const decoded = annotation ? name.substring(1) : name;
  const hashIndex = decoded.indexOf('#');
  const owner = hashIndex >= 0 ? decoded.substring(0, hashIndex) : decoded;
  let result = simpleTypeName(owner);
  if (hashIndex >= 0) {
    let member = decoded.substring(hashIndex + 1);
    const openParen = member.indexOf('(');
    if (openParen >= 0) {
      const closeParen = member.lastIndexOf(')');
      const parameters = member.substring(openParen + 1, closeParen);
      const simpleParameters = parameters
        ? parameters.split(',').map(simpleTypeName).join(',')
        : '';
      member = `${member.substring(0, openParen)}(${simpleParameters})`;
    }
    result += `#${member}`;
  }
  return annotation ? `@${result}` : result;
};

module.exports = { simpleApiName };
