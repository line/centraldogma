import {
  Button,
  Modal,
  ModalBody,
  ModalCloseButton,
  ModalContent,
  ModalHeader,
  ModalOverlay,
  ListItem,
  Text,
  UnorderedList,
} from '@chakra-ui/react';
import {useGetMetadataByProjectNameQuery} from 'dogma/features/api/apiSlice';
import {FaCrown} from 'react-icons/fa';
import {FaUserGroup} from 'react-icons/fa6';

interface ProjectOwnersModalProps {
  projectName: string | null;
  isOpen: boolean;
  onClose: () => void;
}

export const ProjectOwnersModal = ({projectName, isOpen, onClose}: ProjectOwnersModalProps) => {
  const {
    data: ownersMetadata,
    isLoading: isMetadataLoading,
    isError,
    error,
  } = useGetMetadataByProjectNameQuery(projectName ?? '', {
    refetchOnFocus: true,
    skip: !projectName,
  });
  const allMembers = ownersMetadata
      ? Object.entries(ownersMetadata.members).map(([login, member]) => ({
        ...member,
        login: member.login || login,
      }))
      : [];
  const owners = allMembers.filter((member) => member.role === 'OWNER');
  const members = allMembers.filter((member) => member.role !== 'OWNER');
  const errorMessage =
      error && typeof error === 'object' && 'status' in error ? `Failed to load members. (${error.status})` : null;

  return (
      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Project members</ModalHeader>
          <ModalCloseButton />
          <ModalBody>
            {isMetadataLoading ? (
                <Text color="gray.500">Loading members...</Text>
            ) : isError ? (
                <Text color="red.500" mb={2}>
                  {errorMessage || 'Failed to load members.'}
                </Text>
            ) : owners.length === 0 ? (
                <Text color="gray.600">System administrators</Text>
            ) : (
                <>
                  {owners.length > 0 && (
                      <>
                        <Text fontWeight="semibold" mb={2}>
                          <FaCrown
                              style={{
                                marginRight: '8px',
                                display: 'inline-block',
                                color: '#3182CE',
                                marginTop: '1px',
                                marginBottom: '-1px',
                              }}
                          />
                          Owners
                        </Text>
                        <UnorderedList spacing={2} mb={4} stylePosition="outside" pl={4}>
                          {owners.map((member) => (
                              <ListItem
                                  key={member.login}
                                  display="list-item"
                                  sx={{'::marker': {color: 'blue.500', fontWeight: 'bold'}}}
                              >
                                <Text data-testid="project-member-login" as="span" fontWeight="semibold">
                                  {member.login}
                                </Text>
                              </ListItem>
                          ))}
                        </UnorderedList>
                      </>
                  )}
                  {members.length > 0 && (
                      <>
                        <Text fontWeight="semibold" mb={2}>
                          <FaUserGroup
                              style={{
                                marginRight: '8px',
                                display: 'inline-block',
                                color: '#38A169',
                                marginTop: '1px',
                                marginBottom: '-1px',
                              }}
                          />
                          Members
                        </Text>
                        <UnorderedList spacing={2} stylePosition="outside" pl={4}>
                          {members.map((member) => (
                              <ListItem
                                  key={member.login}
                                  display="list-item"
                                  sx={{'::marker': {color: 'green.500', fontWeight: 'bold'}}}
                              >
                                <Text data-testid="project-member-login" as="span" fontWeight="semibold">
                                  {member.login}
                                </Text>
                              </ListItem>
                          ))}
                        </UnorderedList>
                      </>
                  )}
                </>
            )}
          </ModalBody>
        </ModalContent>
      </Modal>
  );
};
