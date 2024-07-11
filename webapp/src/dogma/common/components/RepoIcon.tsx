import { isInternalRepo } from 'dogma/util/repo-util';
import { Box, HStack } from '@chakra-ui/react';
import { GoRepo, GoRepoLocked } from 'react-icons/go';
import { FiArchive } from 'react-icons/fi';
import { ChakraLink } from 'dogma/common/components/ChakraLink';

type RepoProps = {
  projectName: string;
  repoName: string;
  isActive: boolean;
};

export const RepoIcon = ({ projectName, repoName, isActive }: RepoProps) => {
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

  return (
    <ChakraLink fontWeight={'semibold'} href={`/app/projects/${projectName}/repos/${repoName}/tree/head`}>
      <HStack>
        <Box>
          <GoRepo />
        </Box>
        <Box>{repoName}</Box>
      </HStack>
    </ChakraLink>
  );
};
