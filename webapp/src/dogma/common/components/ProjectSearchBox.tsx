import {
  components,
  DropdownIndicatorProps,
  GroupBase,
  OptionBase,
  Select,
  SizeProp,
} from 'chakra-react-select';
import { useEffect, useRef, useState } from 'react';
import { Kbd, useColorMode } from '@chakra-ui/react';
import Router from 'next/router';
import { useGetProjectsQuery } from 'dogma/features/api/apiSlice';
import { ProjectDto } from 'dogma/features/project/ProjectDto';

export interface ProjectSearchBoxProps {
  id: string;
  size?: SizeProp;
  placeholder: string;
  autoFocus?: boolean;
}

export interface ProjectOptionType extends OptionBase {
  value: string;
  label: string;
}

const initialState: ProjectOptionType = {
  value: '',
  label: '',
};

const DropdownIndicator = (
  props: JSX.IntrinsicAttributes & DropdownIndicatorProps<unknown, boolean, GroupBase<unknown>>,
) => {
  return (
    <components.DropdownIndicator {...props}>
      <Kbd>/</Kbd>
    </components.DropdownIndicator>
  );
};

const ProjectSearchBox = ({ id, size, placeholder, autoFocus }: ProjectSearchBoxProps) => {
  const { colorMode } = useColorMode();
  const { data, isLoading } = useGetProjectsQuery({ admin: false });
  const projects = data || [];
  const projectOptions: ProjectOptionType[] = projects.map((project: ProjectDto) => ({
    value: project.name,
    label: project.name,
  }));

  const [selectedOption, setSelectedOption] = useState(initialState);
  const handleChange = (option: ProjectOptionType) => {
    setSelectedOption(option);
  };

  const selectRef = useRef(null);
  useEffect(() => {
    if (selectedOption?.value) {
      selectRef.current.blur();
      Router.push(`/app/projects/${selectedOption.value}`);
    }
  }, [selectedOption?.value]);

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      const target = (e.target as HTMLElement).tagName.toLowerCase();
      if (target == 'textarea' || target == 'input') {
        return;
      }
      if (e.key === '/') {
        e.preventDefault();
        selectRef.current.clearValue();
        selectRef.current.focus();
      } else if (e.key === 'Escape') {
        selectRef.current.blur();
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [selectRef]);

  return (
    <Select
      size={size}
      id={id}
      autoFocus={autoFocus}
      name="project-search"
      options={projectOptions}
      value={selectedOption?.value}
      onChange={(option: ProjectOptionType) => option && handleChange(option)}
      placeholder={placeholder}
      closeMenuOnSelect={true}
      openMenuOnFocus={!autoFocus}
      isClearable={true}
      isSearchable={true}
      ref={selectRef}
      isLoading={isLoading}
      components={{ DropdownIndicator }}
      chakraStyles={{
        control: (baseStyles) => ({
          ...baseStyles,
          backgroundColor: colorMode === 'light' ? 'white' : 'whiteAlpha.50',
        }),
      }}
    />
  );
};

export default ProjectSearchBox;
