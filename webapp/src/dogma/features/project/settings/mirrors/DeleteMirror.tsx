import { Button, useDisclosure } from '@chakra-ui/react';
import { DeleteConfirmationModal } from 'dogma/features/project/settings/DeleteConfirmationModal';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAppDispatch } from 'dogma/hooks';
import { MdDelete } from 'react-icons/md';

export const DeleteMirror = ({
  projectName,
  id,
  deleteMirror,
  isLoading,
}: {
  projectName: string;
  id: string;
  deleteMirror: (projectName: string, id: string) => Promise<void>;
  isLoading: boolean;
}): JSX.Element => {
  const { isOpen, onToggle, onClose } = useDisclosure();
  const dispatch = useAppDispatch();
  const handleDelete = async () => {
    try {
      await deleteMirror(projectName, id);
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
        handleDelete={handleDelete}
        isLoading={isLoading}
      />
    </>
  );
};
