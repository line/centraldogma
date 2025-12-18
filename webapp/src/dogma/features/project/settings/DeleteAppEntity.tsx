import { Button, useDisclosure } from '@chakra-ui/react';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAppDispatch } from 'dogma/hooks';
import { MdDelete } from 'react-icons/md';
import { DeleteConfirmationModal } from 'dogma/common/components/DeleteConfirmationModal';

type DeleteEntityProps = {
  projectName: string;
  repoName?: string;
  id: string;
  entityType: 'member' | 'appIdentity' | 'user';
  deleteEntity: (projectName: string, id: string, repoName?: string) => Promise<void>;
  isLoading: boolean;
};

export const DeleteAppEntity = ({
  projectName,
  repoName,
  id,
  entityType,
  deleteEntity,
  isLoading,
}: DeleteEntityProps): JSX.Element => {
  const { isOpen, onToggle, onClose } = useDisclosure();
  const dispatch = useAppDispatch();
  const handleDelete = async () => {
    try {
      await deleteEntity(projectName, id, repoName);
      dispatch(newNotification(`${entityType} deleted.`, `Successfully deleted ${id}`, 'success'));
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
        type={entityType}
        projectName={projectName}
        repoName={repoName}
        handleDelete={handleDelete}
        isLoading={isLoading}
      />
    </>
  );
};
