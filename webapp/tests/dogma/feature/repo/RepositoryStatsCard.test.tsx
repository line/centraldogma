import { act, cleanup, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RepositoryStatsCard } from 'dogma/features/repo/RepositoryStatsCard';
import { RepositoryStatsDto } from 'dogma/features/repo/RepositoryStatsDto';
import { apiSlice } from 'dogma/features/api/apiSlice';
import { setupStore } from 'dogma/store';
import { renderWithProviders } from 'dogma/util/test-utils';

const stats: RepositoryStatsDto = {
  enabled: true,
  windowHours: 24,
  commitCount: 42,
  recommendedCount: 100,
  updatedAt: '2026-11-16T08:00:00Z',
};

const response = (data = stats) =>
  new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } });

describe('RepositoryStatsCard', () => {
  let store: ReturnType<typeof setupStore>;
  let fetchSpy: jest.SpyInstance;

  beforeEach(() => {
    store = setupStore();
    fetchSpy = jest.spyOn(window, 'fetch').mockImplementation(async () => response());
  });

  it('hides disabled statistics and shows the card when the server enables them', async () => {
    fetchSpy.mockImplementationOnce(async () =>
      response({ ...stats, enabled: false, windowHours: 0, commitCount: 0, recommendedCount: 0 }),
    );
    renderWithProviders(<RepositoryStatsCard projectName="my-project" repoName="my-repo" />, { store });
    const args = { projectName: 'my-project', repoName: 'my-repo' };
    await waitFor(() => {
      expect(apiSlice.endpoints.getRepositoryStats.select(args)(store.getState()).data?.enabled).toBe(false);
    });
    await waitFor(() =>
      expect(screen.queryByRole('region', { name: 'Repository stats' })).not.toBeInTheDocument(),
    );
    expect(screen.queryByText('0/100')).not.toBeInTheDocument();
    expect(fetchSpy).toHaveBeenCalledTimes(1);

    await act(async () => {
      await store.dispatch(
        apiSlice.endpoints.getRepositoryStats.initiate(args, { subscribe: false, forceRefetch: true }),
      );
    });
    expect(await screen.findByText('42/100')).toBeInTheDocument();

    fetchSpy.mockImplementationOnce(async () =>
      response({ ...stats, enabled: false, windowHours: 0, commitCount: 0, recommendedCount: 0 }),
    );
    await act(async () => {
      await store.dispatch(
        apiSlice.endpoints.getRepositoryStats.initiate(args, { subscribe: false, forceRefetch: true }),
      );
    });
    await waitFor(() =>
      expect(screen.queryByRole('region', { name: 'Repository stats' })).not.toBeInTheDocument(),
    );
  });

  it('shows the configured window in the recommendation', async () => {
    fetchSpy.mockImplementation(async () => response({ ...stats, windowHours: 6 }));
    const user = userEvent.setup();
    renderWithProviders(<RepositoryStatsCard projectName="my-project" repoName="my-repo" />, { store });
    expect(await screen.findByText('42/100')).toBeInTheDocument();
    expect(screen.getByRole('progressbar')).toHaveAttribute(
      'aria-valuetext',
      '42 commits in 6 hours; recommended maximum 100',
    );

    await user.hover(screen.getByRole('button', { name: 'Commit frequency information' }));
    expect(await screen.findByRole('tooltip')).toHaveTextContent(
      'Recommended: 100 commits per rolling 6-hour window.',
    );
  });

  it.each([
    { commitCount: 42, display: '42/50', warning: false },
    { commitCount: 50, display: '50/50', warning: false },
    { commitCount: 51, display: '50+/50', warning: true },
  ])('uses the returned recommendation for $commitCount commits', async (count) => {
    const { warning, display, commitCount } = count;
    fetchSpy.mockImplementation(async () => response({ ...stats, commitCount, recommendedCount: 50 }));
    const user = userEvent.setup();
    renderWithProviders(<RepositoryStatsCard projectName="my-project" repoName="my-repo" />, { store });

    expect(await screen.findByText(display)).toBeInTheDocument();
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', String(Math.min(commitCount, 50)));
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuemax', '50');
    await user.hover(screen.getByRole('button', { name: 'Commit frequency information' }));
    const tooltip = await screen.findByRole('tooltip');
    expect(tooltip).toHaveTextContent('Recommended: 50 commits per rolling 24-hour window.');
    if (warning) {
      expect(tooltip).toHaveTextContent('Above recommended frequency');
    } else {
      expect(tooltip).not.toHaveTextContent('Above recommended frequency');
    }
  });

  afterEach(() => {
    cleanup();
    store.dispatch(apiSlice.util.resetApiState());
    jest.useRealTimers();
    jest.restoreAllMocks();
  });

  it.each([
    { commitCount: 0, display: '0/100' },
    { commitCount: 42, display: '42/100' },
    { commitCount: 100, display: '100/100' },
    { commitCount: 101, display: '100+/100' },
  ])('displays $display for commitCount=$commitCount', async ({ display, ...count }) => {
    fetchSpy.mockImplementation(async () => response({ ...stats, ...count }));
    const user = userEvent.setup();

    renderWithProviders(<RepositoryStatsCard projectName="my-project" repoName="my-repo" />, { store });

    expect(await screen.findByText(display, { exact: true })).toBeInTheDocument();
    expect(screen.getByText('Commit frequency')).toBeInTheDocument();
    const progress = screen.getByRole('progressbar', { name: 'Commit frequency' });
    expect(progress).toHaveAttribute('aria-valuenow', String(Math.min(count.commitCount, 100)));
    expect(progress).toHaveAttribute('aria-valuemax', '100');
    expect(screen.queryByText('Above recommended frequency')).not.toBeInTheDocument();
    await user.hover(screen.getByRole('button', { name: 'Commit frequency information' }));
    const tooltip = await screen.findByRole('tooltip');
    if (count.commitCount > stats.recommendedCount) {
      expect(tooltip).toHaveTextContent('Above recommended frequency');
    } else {
      expect(tooltip).not.toHaveTextContent('Above recommended frequency');
    }
    const request = fetchSpy.mock.calls[0][0] as Request;
    expect(request.method).toBe('GET');
    expect(new URL(request.url, 'http://localhost').pathname).toBe(
      '/api/v1/projects/my-project/repos/my-repo/stats',
    );
  });

  it('shows the recommendation when hovering over the information button', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RepositoryStatsCard projectName="my-project" repoName="my-repo" />, { store });
    expect(await screen.findByText('42/100')).toBeInTheDocument();
    expect(screen.queryByText('Recommended: 100 commits per rolling 24-hour window.')).not.toBeInTheDocument();

    await user.hover(screen.getByRole('button', { name: 'Commit frequency information' }));

    expect(await screen.findByRole('tooltip')).toHaveTextContent(
      'Recommended: 100 commits per rolling 24-hour window.',
    );
  });

  it('shows loading instead of zero while the stats request is pending', async () => {
    let complete: (response: Response) => void;
    fetchSpy.mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          complete = resolve;
        }),
    );

    renderWithProviders(<RepositoryStatsCard projectName="my-project" repoName="my-repo" />, { store });

    expect(screen.getByRole('status', { name: 'Loading repository stats' })).toBeInTheDocument();
    expect(screen.queryByText('0/100', { exact: true })).not.toBeInTheDocument();
    await waitFor(() => expect(fetchSpy).toHaveBeenCalledTimes(1));
    await act(async () => complete(response()));
    expect(await screen.findByText('42/100')).toBeInTheDocument();
  });

  it('allows retrying a failed request without displaying zero', async () => {
    fetchSpy.mockResolvedValueOnce(new Response('Unavailable', { status: 503 }));
    const user = userEvent.setup();

    renderWithProviders(<RepositoryStatsCard projectName="my-project" repoName="my-repo" />, { store });

    expect(await screen.findByText('Repository stats unavailable.')).toBeInTheDocument();
    expect(screen.queryByText('0/100', { exact: true })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('42/100')).toBeInTheDocument();
    expect(screen.queryByText('Repository stats unavailable.')).not.toBeInTheDocument();
  });

  it('waits for both repository parameters before requesting stats', async () => {
    const { rerender } = renderWithProviders(<RepositoryStatsCard projectName="" repoName="my-repo" />, {
      store,
    });
    expect(fetchSpy).not.toHaveBeenCalled();

    rerender(<RepositoryStatsCard projectName="my-project" repoName="" />);
    expect(fetchSpy).not.toHaveBeenCalled();

    rerender(<RepositoryStatsCard projectName="my-project" repoName="my-repo" />);
    expect(await screen.findByText('42/100')).toBeInTheDocument();
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });

  it("does not display another repository's stats while loading", async () => {
    const { rerender } = renderWithProviders(
      <RepositoryStatsCard projectName="my-project" repoName="first" />,
      {
        store,
      },
    );
    expect(await screen.findByText('42/100')).toBeInTheDocument();

    fetchSpy.mockImplementationOnce(() => new Promise<Response>(() => {}));
    rerender(<RepositoryStatsCard projectName="my-project" repoName="second" />);

    expect(screen.getByRole('status', { name: 'Loading repository stats' })).toBeInTheDocument();
    expect(screen.queryByText('42/100')).not.toBeInTheDocument();
  });

  it('keeps the previous count visible when a refresh fails', async () => {
    fetchSpy.mockImplementationOnce(async () => response({ ...stats, commitCount: 101 }));
    renderWithProviders(<RepositoryStatsCard projectName="my-project" repoName="my-repo" />, { store });
    expect(await screen.findByText('100+/100')).toBeInTheDocument();

    fetchSpy.mockResolvedValueOnce(new Response('Unavailable', { status: 503 }));
    await act(async () => {
      await store.dispatch(
        apiSlice.endpoints.getRepositoryStats.initiate(
          { projectName: 'my-project', repoName: 'my-repo' },
          { subscribe: false, forceRefetch: true },
        ),
      );
    });

    expect(await screen.findByText('Could not refresh repository stats.')).toBeInTheDocument();
    expect(screen.getByText('100+/100')).toBeInTheDocument();
    await userEvent.setup().hover(screen.getByRole('button', { name: 'Commit frequency information' }));
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Above recommended frequency');
  });

  it('polls and clears the warning when commit frequency falls below the recommendation', async () => {
    jest.useFakeTimers();
    const user = userEvent.setup({ advanceTimers: jest.advanceTimersByTime });
    fetchSpy.mockImplementationOnce(async () => response({ ...stats, commitCount: 101 }));
    renderWithProviders(<RepositoryStatsCard projectName="my-project" repoName="my-repo" />, { store });
    expect(await screen.findByText('100+/100')).toBeInTheDocument();
    await user.hover(screen.getByRole('button', { name: 'Commit frequency information' }));
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Above recommended frequency');

    await act(async () => {
      await jest.advanceTimersByTimeAsync(60_000);
    });
    jest.useRealTimers();

    expect(fetchSpy).toHaveBeenCalledTimes(2);
    expect(await screen.findByText('42/100')).toBeInTheDocument();
    expect(screen.queryByText('Above recommended frequency')).not.toBeInTheDocument();
  });
});
