import { Button, useDisclosure } from '@chakra-ui/react';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAppDispatch } from 'dogma/hooks';
import { MdDelete } from 'react-icons/md';
import { DeleteConfirmationModal } from '../DeleteConfirmationModal';

export const DeleteToken = ({
  projectName,
  repoName,
  id,
  deleteToken,
  isLoading,
}: {
  projectName: string;
  repoName?: string;
  id: string;
  deleteToken: (projectName: string, id: string, repoName?: string) => Promise<void>;
  isLoading: boolean;
}): JSX.Element => {
  const { isOpen, onToggle, onClose } = useDisclosure();
  const dispatch = useAppDispatch();
  const handleDelete = async () => {
    try {
      await deleteToken(projectName, id, repoName);
      dispatch(newNotification('Token deleted.', `Successfully deleted ${id}`, 'success'));
      onClose();
    } catch (error) {
      dispatch(newNotification(`Failed to delete ${id}`, ErrorMessageParser.parse(error), 'error'));
    }
  };
  return (
    <>
      <Button leftIcon={<MdDelete />} colorScheme="red" size="sm" variant="ghost" onClick={onToggle}>
        Delete
      </Button>
      <DeleteConfirmationModal
        isOpen={isOpen}
        onClose={onClose}
        id={id}
        type={'token'}
        projectName={projectName}
        handleDelete={handleDelete}
        isLoading={isLoading}
      />
    </>
  );
};
