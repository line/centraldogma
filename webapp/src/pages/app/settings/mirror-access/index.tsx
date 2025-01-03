import { Box, Button, Flex, Spacer } from '@chakra-ui/react';
import SettingView from 'dogma/features/settings/SettingView';
import MirrorAccessControlList from 'dogma/features/settings/mirror-access/MirrorAccessControlList';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { FaPlus } from 'react-icons/fa6';

const MirrorAccessControlListPage = () => {
  return (
    <SettingView currentTab={'Mirror Access Control'}>
      <Box p="2">
        <Flex>
          <Spacer />
          <Button
            as={ChakraLink}
            href="/app/settings/mirror-access/new"
            colorScheme="teal"
            size={'sm'}
            rightIcon={<FaPlus />}
          >
            New Mirror Access Control
          </Button>
        </Flex>
        <MirrorAccessControlList />
      </Box>
    </SettingView>
  );
};

export default MirrorAccessControlListPage;
