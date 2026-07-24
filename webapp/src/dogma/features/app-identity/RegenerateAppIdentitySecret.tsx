import {
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

export const RegenerateAppIdentitySecret = ({ appId, hidden }: { appId: string; hidden: boolean }) => {
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
      <Button
        size="sm"
        colorScheme="orange"
        hidden={hidden}
        variant="ghost"
        onClick={onToggle}
        leftIcon={<MdRefresh />}
      >
        Regenerate secret
      </Button>
      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Are you sure?</ModalHeader>
          <ModalCloseButton />
          <ModalBody>
            <Text>
              Regenerate the secret of application identity {`${appId}`}? The current secret is revoked
              immediately and clients using it will no longer be able to authenticate.
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
