import {
  Accordion,
  AccordionButton,
  AccordionIcon,
  AccordionItem,
  AccordionPanel,
  Table,
  TableCaption,
  TableContainer,
  Tbody,
  Td,
  Text,
  Th,
  Thead,
  Tr,
} from '@chakra-ui/react';
import { ChakraLink } from 'dogma/common/components/ChakraLink';

const legends = [
  { path: '$', description: 'the root object/element' },
  { path: '@', description: 'the current object/element' },
  { path: '. or []', description: 'child operator' },
  { path: '..', description: 'recursive descent. JSONPath borrows this syntax from E4X.' },
  { path: '*', description: 'wildcard. All objects/elements regardless their names.' },
  {
    path: '[]',
    description:
      'subscript operator. XPath uses it to iterate over element collections and for predicates. In Javascript and JSON it is the native array operator.',
  },
  {
    path: '[,]',
    description:
      'Union operator in XPath results in a combination of node sets. JSONPath allows alternate names or array indices as a set.',
  },
  { path: '[start:end:step]', description: 'array slice operator borrowed from ES4.' },
  { path: '?()', description: 'applies a filter (script) expression.' },
  { path: '()', description: 'script expression, using the underlying script engine.' },
];

export const JsonPathLegend = () => {
  return (
    <Accordion allowToggle>
      <AccordionItem>
        <AccordionButton>
          <Text>JSONPath expressions</Text>
          <AccordionIcon />
        </AccordionButton>
        <AccordionPanel pb={4}>
          <TableContainer>
            <Table size="sm" whiteSpace="normal">
              <TableCaption>
                <ChakraLink href="https://goessner.net/articles/JsonPath/index.html#e2" target="_blank">
                  https://goessner.net/articles/JsonPath/index.html#e2
                </ChakraLink>
              </TableCaption>
              <Thead>
                <Tr>
                  <Th>JSONPath</Th>
                  <Th>Description</Th>
                </Tr>
              </Thead>
              <Tbody>
                {legends.map((legend, i) => (
                  <Tr key={i}>
                    <Td>{legend.path}</Td>
                    <Td>
                      <Text noOfLines={2}>{legend.description}</Text>
                    </Td>
                  </Tr>
                ))}
              </Tbody>
            </Table>
          </TableContainer>
        </AccordionPanel>
      </AccordionItem>
    </Accordion>
  );
};
