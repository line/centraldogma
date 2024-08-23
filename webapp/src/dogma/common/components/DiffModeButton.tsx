import React from 'react';
import { UseRadioProps } from '@chakra-ui/radio/dist/use-radio';
import { Box, HStack, Tooltip, useRadio, useRadioGroup } from '@chakra-ui/react';

const RadioCard = (props: { left: boolean; children: React.ReactNode } & UseRadioProps) => {
  const { getInputProps, getRadioProps } = useRadio(props);

  const input = getInputProps();
  const checkbox = getRadioProps();

  return (
    <Box as="label">
      <input {...input} />
      <Tooltip label={`Switch to the ${input.value.toLowerCase()} view`}>
        <Box
          {...checkbox}
          cursor="pointer"
          borderLeftRadius={props.left ? 'md' : '0'}
          borderRightRadius={props.left ? '0' : 'md'}
          borderWidth="1px"
          _checked={{
            bg: 'teal.600',
            color: 'white',
            borderColor: 'teal.600',
          }}
          _focus={{
            boxShadow: 'outline',
          }}
          fontWeight={'normal'}
          px={5}
          py={1}
          marginLeft={-1}
          marginRight={-1}
        >
          {props.children}
        </Box>
      </Tooltip>
    </Box>
  );
};

const DiffModeButton = (props: { onChange: (value: string) => void }) => {
  const options = ['Split', 'Unified'];

  const { getRootProps, getRadioProps } = useRadioGroup({
    name: 'diff-view',
    defaultValue: 'Split',
    onChange: props.onChange,
  });

  const group = getRootProps();

  return (
    <HStack {...group}>
      {options.map((value, index) => {
        const radio = getRadioProps({ value });
        return (
          <RadioCard key={value} {...radio} left={index === 0}>
            {value}
          </RadioCard>
        );
      })}
    </HStack>
  );
};

export default DiffModeButton;
