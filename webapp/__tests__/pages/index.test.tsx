import HomePage from 'pages/index';
import { ChakraProvider, theme } from '@chakra-ui/react';
import { renderWithProviders } from 'dogma/util/test-utils';

// TODO(ikhoon): Revive this test
xdescribe('Home', () => {
  it('renders a heading', () => {
    const result = renderWithProviders(
      <ChakraProvider theme={theme}>
        <HomePage />
      </ChakraProvider>,
    );
    const heading = result.getByRole('heading', {
      name: /Welcome to Central Dogma!/i,
    });

    expect(heading).toBeInTheDocument();
  });
});
