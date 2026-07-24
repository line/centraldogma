import {
  Box,
  Button,
  HStack,
  Modal,
  ModalBody,
  ModalCloseButton,
  ModalContent,
  ModalFooter,
  ModalHeader,
  ModalOverlay,
  Text,
  Tooltip,
  useDisclosure,
} from '@chakra-ui/react';
import { apiSlice, useRegenerateAppIdentitySecretMutation } from 'dogma/features/api/apiSlice';
import { AppIdentityDto } from 'dogma/features/app-identity/AppIdentity';
import { DisplaySecretModal } from 'dogma/features/app-identity/DisplaySecretModal';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAppDispatch } from 'dogma/hooks';
import { useEffect, useRef, useState } from 'react';
import { MdRefresh } from 'react-icons/md';

export const RegenerateAppIdentitySecret = ({
  appId,
  hidden,
  disabled,
}: {
  appId: string;
  hidden: boolean;
  disabled?: boolean;
}) => {
  const { isOpen, onToggle, onClose } = useDisclosure();
  const {
    isOpen: isSecretModalOpen,
    onToggle: onSecretModalToggle,
    onClose: onSecretModalClose,
  } = useDisclosure();
  const dispatch = useAppDispatch();
  const [regenerateSecret, { isLoading, reset }] = useRegenerateAppIdentitySecretMutation();
  const [appIdentityDetail, setAppIdentityDetail] = useState<AppIdentityDto | null>(null);
  const mounted = useRef(true);
  const invalidationPending = useRef(false);
  useEffect(() => {
    mounted.current = true;
    // Refresh the list even if this component is unmounted before the secret modal is closed.
    return () => {
      mounted.current = false;
      if (invalidationPending.current) {
        invalidationPending.current = false;
        dispatch(apiSlice.util.invalidateTags(['AppIdentity']));
      }
    };
  }, [dispatch]);
  const handleRegenerate = async () => {
    try {
      const response = await regenerateSecret({ appId }).unwrap();
      if (!mounted.current) {
        // Unmounted while the request was in flight; refresh the list right away.
        dispatch(apiSlice.util.invalidateTags(['AppIdentity']));
        return;
      }
      invalidationPending.current = true;
      setAppIdentityDetail(response);
      onClose();
      onSecretModalToggle();
    } catch (error) {
      dispatch(
        newNotification(
          `Failed to regenerate the secret of ${appId}`,
          ErrorMessageParser.parse(error),
          'error',
        ),
      );
    }
  };
  const handleSecretModalClose = () => {
    onSecretModalClose();
    setAppIdentityDetail(null);
    reset();
    // Refetch after the secret modal is closed; refetching earlier remounts this table cell
    // and closes the modal before the user sees the new secret.
    invalidationPending.current = false;
    dispatch(apiSlice.util.invalidateTags(['AppIdentity']));
  };
  return (
    <>
      <Tooltip label="Deactivate the token first to regenerate its secret." isDisabled={hidden || !disabled}>
        {/* A disabled button does not emit hover events, so the tooltip wraps a Box instead. */}
        <Box display="inline-block" hidden={hidden}>
          <Button
            size="sm"
            colorScheme="orange"
            isDisabled={disabled}
            variant="ghost"
            onClick={onToggle}
            leftIcon={<MdRefresh />}
          >
            Regenerate secret
          </Button>
        </Box>
      </Tooltip>
      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Are you sure?</ModalHeader>
          <ModalCloseButton />
          <ModalBody>
            <Text>
              Regenerate the secret of application identity {`${appId}`}? The app identity stays inactive and
              the new secret will not work until the app identity is activated.
            </Text>
          </ModalBody>
          <ModalFooter>
            <HStack spacing={3}>
              <Button colorScheme="orange" variant="outline" onClick={onClose}>
                Cancel
              </Button>
              <Button
                colorScheme="orange"
                onClick={handleRegenerate}
                isLoading={isLoading}
                loadingText="Regenerating"
              >
                Regenerate
              </Button>
            </HStack>
          </ModalFooter>
        </ModalContent>
      </Modal>
      <DisplaySecretModal
        isOpen={isSecretModalOpen}
        onClose={handleSecretModalClose}
        response={appIdentityDetail}
        title="Secret regenerated"
      />
    </>
  );
};
