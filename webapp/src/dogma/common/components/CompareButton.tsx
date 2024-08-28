import {
  Button,
  FormControl,
  Icon,
  Input,
  Popover,
  PopoverArrow,
  PopoverBody,
  PopoverContent,
  PopoverTrigger,
  Stack,
  useDisclosure,
} from '@chakra-ui/react';
import { IoMdArrowDropdown } from 'react-icons/io';
import { IoGitCompareSharp } from 'react-icons/io5';
import React from 'react';
import { useForm } from 'react-hook-form';
import Router from 'next/router';
import FieldErrorMessage from 'dogma/common/components/form/FieldErrorMessage';

type CompareButtonProps = {
  projectName: string;
  repoName: string;
  headRevision: number;
};

type FormData = {
  baseRevision: string;
};

const CompareButton = ({ projectName, repoName, headRevision }: CompareButtonProps) => {
  const { isOpen, onToggle, onClose } = useDisclosure();
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormData>();
  const onSubmit = (data: FormData) => {
    Router.push(
      `/app/projects/${projectName}/repos/${repoName}/compare/${headRevision}/base/${data.baseRevision}`,
    );
    reset();
    onClose();
  };

  return (
    <Popover placement="bottom" isOpen={isOpen} onClose={onClose}>
      <PopoverTrigger>
        <Button colorScheme="teal" size="sm" onClick={onToggle} rightIcon={<IoMdArrowDropdown />}>
          Compare
        </Button>
      </PopoverTrigger>
      <PopoverContent width={'240px'}>
        <PopoverArrow />
        <PopoverBody>
          <form onSubmit={handleSubmit(onSubmit)}>
            <Stack direction={'row'}>
              <FormControl isRequired>
                <Input
                  type="number"
                  placeholder={`Rev 1..${headRevision - 1}`}
                  autoFocus
                  {...register('baseRevision', { required: true, min: 1, max: headRevision - 1 })}
                />
                {errors.baseRevision && (
                  <FieldErrorMessage
                    error={errors.baseRevision}
                    fieldName="Revision"
                    errorMessage={'Invalid revision'}
                  />
                )}
              </FormControl>
              <Button type="submit" colorScheme="green" background={'green.50'} variant="outline">
                <Icon as={IoGitCompareSharp} />
              </Button>
            </Stack>
          </form>
        </PopoverBody>
      </PopoverContent>
    </Popover>
  );
};

export default CompareButton;
