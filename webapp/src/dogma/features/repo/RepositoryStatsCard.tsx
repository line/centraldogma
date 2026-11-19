import { InfoOutlineIcon } from '@chakra-ui/icons';
import {
  Box,
  Button,
  HStack,
  IconButton,
  Progress,
  Skeleton,
  Text,
  Tooltip,
  useColorModeValue,
} from '@chakra-ui/react';
import { useGetRepositoryStatsQuery } from 'dogma/features/api/apiSlice';

export const RepositoryStatsCard = ({ projectName, repoName }: { projectName: string; repoName: string }) => {
  const backgroundColor = useColorModeValue('white', 'gray.800');
  const descriptionColor = useColorModeValue('gray.600', 'gray.400');
  const warningColor = useColorModeValue('red.500', 'red.200');
  const {
    currentData: stats,
    isFetching,
    isError,
    refetch,
  } = useGetRepositoryStatsQuery(
    { projectName, repoName },
    {
      skip: !projectName || !repoName,
      refetchOnMountOrArgChange: 60,
      pollingInterval: 60_000,
    },
  );

  if (stats && !stats.enabled) {
    return null;
  }

  const aboveRecommended = stats && stats.commitCount > stats.recommendedCount;
  const displayedCount = stats && Math.min(stats.commitCount, stats.recommendedCount);
  const countLabel = `${displayedCount}${aboveRecommended ? '+' : ''}`;

  return (
    <Box
      as="section"
      aria-label="Repository stats"
      borderWidth="1px"
      borderRadius="md"
      bg={backgroundColor}
      p={4}
      ml="auto"
      w="64"
      flexShrink={0}
    >
      <HStack justifyContent="space-between" mb={2}>
        <HStack spacing={1}>
          <Text fontSize="sm" fontWeight="medium">
            Commit frequency
          </Text>
          <Tooltip
            label={
              stats && (
                <Box>
                  <Text>
                    Recommended: {stats.recommendedCount} commits per rolling {stats.windowHours}-hour window.
                  </Text>
                  {aboveRecommended && (
                    <Box mt={3}>
                      <Text fontWeight="semibold">Above recommended frequency</Text>
                    </Box>
                  )}
                </Box>
              )
            }
            hasArrow
          >
            <IconButton
              aria-label="Commit frequency information"
              icon={<InfoOutlineIcon />}
              variant="ghost"
              size="xs"
              color={aboveRecommended ? warningColor : descriptionColor}
            />
          </Tooltip>
        </HStack>
        {stats && (
          <Text fontSize="sm" fontWeight="semibold">
            {countLabel}/{stats.recommendedCount}
          </Text>
        )}
      </HStack>
      {stats ? (
        <>
          <Progress
            aria-label="Commit frequency"
            aria-valuetext={`${countLabel} commits in ${stats.windowHours} hours; recommended maximum ${stats.recommendedCount}`}
            value={displayedCount}
            max={stats.recommendedCount}
            size="md"
            colorScheme={aboveRecommended ? 'red' : 'teal'}
            borderRadius="full"
          />
          <Text fontSize="xs" color={descriptionColor} mt={2} textAlign="right">
            Updated at <time dateTime={stats.updatedAt}>{new Date(stats.updatedAt).toLocaleTimeString()}</time>
          </Text>
        </>
      ) : isError ? null : (
        <Skeleton height="3" role="status" aria-label="Loading repository stats" />
      )}
      {isError && (
        <>
          <Text fontSize="sm" mt={3}>
            {stats ? 'Could not refresh repository stats.' : 'Repository stats unavailable.'}
          </Text>
          <Button variant="link" size="sm" onClick={() => refetch()} isLoading={isFetching}>
            Retry
          </Button>
        </>
      )}
    </Box>
  );
};
