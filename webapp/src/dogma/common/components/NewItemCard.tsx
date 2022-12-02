import { AddIcon } from '@chakra-ui/icons';
import {
  Accordion,
  AccordionButton,
  AccordionIcon,
  AccordionItem,
  AccordionPanel,
  Box,
  Flex,
  IconButton,
  Input,
  Spacer,
  Text,
} from '@chakra-ui/react';

export type NewItemCardProps = {
  title: string;
  label: string;
  placeholder: string;
};

export const NewItemCard = ({ title, label, placeholder }: NewItemCardProps) => {
  return (
    <Accordion allowToggle>
      <AccordionItem>
        <AccordionButton>
          <Box flex="1" textAlign="left">
            {title}
          </Box>
          <AccordionIcon />
        </AccordionButton>
        <AccordionPanel pb={4}>
          <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
            <Text>{label}</Text>
            <Input placeholder={placeholder} />
            <Spacer />
            <IconButton color="teal" aria-label="Create" size="md" icon={<AddIcon />} />
          </Flex>
        </AccordionPanel>
      </AccordionItem>
    </Accordion>
  );
};
