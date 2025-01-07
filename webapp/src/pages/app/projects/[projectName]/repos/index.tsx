import { useRouter } from 'next/router';

const ReposPage = () => {
  const router = useRouter();

  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  router.replace(`/app/projects/${projectName}`);

  return <></>;
};

export default ReposPage;
