import { useRouter } from 'next/router';

const RepoPage = () => {
  const router = useRouter();

  const repoName = router.query.repoName ? (router.query.repoName as string) : '';
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  router.replace(`/app/projects/${projectName}/repos/${repoName}/tree/head/`);

  return <></>;
};

export default RepoPage;
