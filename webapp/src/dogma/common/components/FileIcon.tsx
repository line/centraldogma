import { LuFile, LuFileJson } from 'react-icons/lu';
import { BsFiletypeYml } from 'react-icons/bs';
import React from 'react';
import { Icon } from '@chakra-ui/react';

type FileIconProps = {
  fileName: string;
};

export const FileIcon = ({ fileName }: FileIconProps) => {
  const extension = fileName.substring(fileName.lastIndexOf('.') + 1);
  switch (extension) {
    case 'json':
      return <Icon as={LuFileJson} />;
    case 'yaml':
    case 'yml':
      return <Icon as={BsFiletypeYml} />;
    default:
      return <Icon as={LuFile} />;
  }
};
