/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
import {
  Alert,
  AlertIcon,
  Badge,
  Box,
  HStack,
  Modal,
  ModalBody,
  ModalCloseButton,
  ModalContent,
  ModalHeader,
  ModalOverlay,
  Spinner,
  Tag,
  Text,
  Wrap,
  WrapItem,
} from '@chakra-ui/react';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export interface K8sPreviewResult {
  ok: boolean;
  error?: string;
  // The resulting ClusterLoadAssignment JSON.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  assignment?: any;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function localityLabel(locality: any): string {
  if (!locality) {
    return '(no locality)';
  }
  const parts = [locality.region, locality.zone, locality.subZone].filter(Boolean);
  return parts.length > 0 ? parts.join(' / ') : '(no locality)';
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function socketAddress(lbEndpoint: any): string {
  const sa = lbEndpoint?.endpoint?.address?.socketAddress;
  if (!sa) {
    return '(unknown)';
  }
  return `${sa.address}:${sa.portValue}`;
}

interface Props {
  isOpen: boolean;
  onClose: () => void;
  isLoading: boolean;
  result: K8sPreviewResult | null;
}

// Shows the endpoints a Kubernetes aggregator would generate, resolved from Kubernetes without persisting.
export const K8sAggregatorPreviewModal = ({ isOpen, onClose, isLoading, result }: Props) => {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const localities: any[] = result?.assignment?.endpoints ?? [];
  const total = localities.reduce(
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (sum: number, loc: any) => sum + (Array.isArray(loc?.lbEndpoints) ? loc.lbEndpoints.length : 0),
    0,
  );

  return (
    <Modal isOpen={isOpen} onClose={onClose} size="2xl" scrollBehavior="inside">
      <ModalOverlay />
      <ModalContent>
        <ModalHeader>Endpoint preview</ModalHeader>
        <ModalCloseButton />
        <ModalBody pb={6}>
          {isLoading || !result ? (
            <HStack color="gray.500">
              <Spinner size="sm" />
              <Text>Resolving endpoints from Kubernetes…</Text>
            </HStack>
          ) : !result.ok ? (
            <Alert status="error" borderRadius="md" fontSize="sm">
              <AlertIcon />
              {result.error || 'Failed to resolve endpoints.'}
            </Alert>
          ) : total === 0 ? (
            <Alert status="warning" borderRadius="md" fontSize="sm">
              <AlertIcon />
              No endpoints were resolved. The service may have no matching endpoints.
            </Alert>
          ) : (
            <Box>
              <Text fontSize="sm" color="gray.500" mb={3}>
                Would generate <b>{total}</b> endpoint{total === 1 ? '' : 's'} across {localities.length}{' '}
                localit{localities.length === 1 ? 'y' : 'ies'}. Nothing has been saved.
              </Text>
              {localities.map((loc, i) => (
                <Box key={i} borderWidth="1px" borderRadius="md" p={3} mb={2}>
                  <HStack mb={2} spacing={2}>
                    <Badge colorScheme="purple">{localityLabel(loc.locality)}</Badge>
                    <Text fontSize="xs" color="gray.500">
                      priority {loc.priority ?? 0}
                      {loc.loadBalancingWeight != null && ` · weight ${loc.loadBalancingWeight}`}
                    </Text>
                  </HStack>
                  <Wrap>
                    {(loc.lbEndpoints ?? []).map(
                      // eslint-disable-next-line @typescript-eslint/no-explicit-any
                      (lb: any, j: number) => (
                        <WrapItem key={j}>
                          <Tag size="sm" colorScheme="teal" variant="subtle">
                            {socketAddress(lb)}
                          </Tag>
                        </WrapItem>
                      ),
                    )}
                  </Wrap>
                </Box>
              ))}
            </Box>
          )}
        </ModalBody>
      </ModalContent>
    </Modal>
  );
};
