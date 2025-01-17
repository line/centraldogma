import { Button, useDisclosure } from '@chakra-ui/react';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAppDispatch } from 'dogma/hooks';
import { MdDelete } from 'react-icons/md';
import { DeleteConfirmationModal } from 'dogma/common/components/DeleteConfirmationModal';

export const DeleteMirror = ({
  projectName,
  repoName,
  id,
  deleteMirror,
  isLoading,
}: {
  projectName: string;
  repoName: string;
  id: string;
  deleteMirror: (projectName: string, repoName: string, id: string) => Promise<void>;
  isLoading: boolean;
}): JSX.Element => {
  const { isOpen, onToggle, onClose } = useDisclosure();
  const dispatch = useAppDispatch();
  const handleDelete = async () => {
    try {
      await deleteMirror(projectName, repoName, id);
      dispatch(newNotification('Mirror deleted.', `Successfully deleted ${id}`, 'success'));
      onClose();
    } catch (error) {
      dispatch(newNotification(`Failed to delete ${id}`, ErrorMessageParser.parse(error), 'error'));
    }
  };
  return (
    <>
      <Button leftIcon={<MdDelete />} colorScheme="red" size="sm" onClick={onToggle}>
        Delete
      </Button>
      <DeleteConfirmationModal
        isOpen={isOpen}
        onClose={onClose}
        id={id}
        type={'mirror'}
        projectName={projectName}
        repoName={repoName}
        handleDelete={handleDelete}
        isLoading={isLoading}
      />
    </>
  );
};
