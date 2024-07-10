import { Breadcrumb, BreadcrumbItem, BreadcrumbLink, Text } from '@chakra-ui/react';
import { FcNext } from 'react-icons/fc';
import NextLink from 'next/link';

interface BreadcrumbsProps {
  path: string;
  omitIndexList?: number[];
  unlinkedList?: number[];
  replaces?: { [key: number]: string };
  suffixes?: { [key: number]: string };
}

export const Breadcrumbs = ({
  path,
  omitIndexList = [],
  unlinkedList = [],
  replaces = {},
  // /project/projectName/repos/repoName -> /project/projectName/repos/repoName/tree/head
  suffixes = {},
}: BreadcrumbsProps) => {
  const asPathNestedRoutes = path
    // If the path belongs to a file, the top level should be a directory
    .replace('/files/head', '/tree/head')
    .split('/')
    .filter((v) => v.length > 0);
  if (asPathNestedRoutes && asPathNestedRoutes[asPathNestedRoutes.length - 1].startsWith('#')) {
    asPathNestedRoutes.pop();
  }
  const prefixes: string[] = [];
  return (
    <Breadcrumb spacing="8px" separator={<FcNext />} mb={8} fontWeight="medium" fontSize="2xl">
      {asPathNestedRoutes.map((page, i) => {
        prefixes.push(page);
        const item = replaces[i] || page;
        if (omitIndexList.includes(i)) {
          return null;
        }

        return (
          <BreadcrumbItem key={i}>
            {!unlinkedList.includes(i) && i < asPathNestedRoutes.length - 1 ? (
              <BreadcrumbLink
                as={NextLink}
                href={`/${prefixes.join('/')}${suffixes[i] || ''}`}
                paddingBottom={1}
              >
                {decodeURI(item)}
              </BreadcrumbLink>
            ) : (
              <Text paddingBottom={1}> {decodeURI(item)}</Text>
            )}
          </BreadcrumbItem>
        );
      })}
    </Breadcrumb>
  );
};
