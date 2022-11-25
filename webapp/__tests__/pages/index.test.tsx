import { render, screen } from '@testing-library/react';
import HomePage from '@/pages/index';

describe('Home', () => {
  it('renders a heading', () => {
    render(<HomePage />);

    const heading = screen.getByRole('heading', {
      name: /Welcome to Central Dogma!/i,
    });

    expect(heading).toBeInTheDocument();
  });
});
