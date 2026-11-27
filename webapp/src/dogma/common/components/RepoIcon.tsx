import { isInternalRepo } from 'dogma/util/repo-util';
import { Badge, Box, HStack, Tooltip } from '@chakra-ui/react';
import { GoRepo, GoRepoLocked } from 'react-icons/go';
import { FiArchive } from 'react-icons/fi';
import { ChakraLink } from 'dogma/common/components/ChakraLink';

type RepoProps = {
  projectName: string;
  repoName: string;
  isActive: boolean;
  isPublic?: boolean;
  isAccessible?: boolean;
};

export const RepoIcon = ({ projectName, repoName, isActive, isPublic, isAccessible = true }: RepoProps) => {
  const isInternal = isInternalRepo(repoName);
  if (!isActive) {
    return (
      <HStack color={'gray'}>
        <Box>
          <FiArchive />
        </Box>
        <Box>{repoName}</Box>
      </HStack>
    );
  }

  if (isInternal) {
    return (
      <ChakraLink fontWeight={'semibold'} href={`/app/projects/${projectName}/repos/${repoName}/tree/head`}>
        <HStack color={'brown'}>
          <Box>
            <GoRepoLocked />
          </Box>
          <Box>{repoName}</Box>
        </HStack>
      </ChakraLink>
    );
  }

  if (!isAccessible) {
    return (
      <Tooltip label="You don't have permission to access this repository">
        <HStack
          color={'gray.400'}
          cursor={'not-allowed'}
          fontWeight={'semibold'}
          tabIndex={0}
          aria-label={`${repoName}: no permission to access`}
        >
          <Box>
            <GoRepoLocked />
          </Box>
          <Box>{repoName}</Box>
        </HStack>
      </Tooltip>
    );
  }

  return (
    <ChakraLink fontWeight={'semibold'} href={`/app/projects/${projectName}/repos/${repoName}/tree/head`}>
      <HStack>
        <Box>
          <GoRepo />
        </Box>
        <Box>{repoName}</Box>
        {isPublic && (
          <Badge fontSize="x-small" colorScheme="teal" variant="outline" borderRadius="full" px={2}>
            Public
          </Badge>
        )}
      </HStack>
    </ChakraLink>
  );
};
