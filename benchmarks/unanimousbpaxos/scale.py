from .unanimousbpaxos import *


def _main(args) -> None:
    class ScaleUnanimousBPaxosSuite(UnanimousBPaxosSuite):
        def args(self) -> Dict[Any, Any]:
            return vars(args)

        def inputs(self) -> Collection[Input]:
            return [
                Input(
                    net_name = 'SingleSwitchNet',
                    f = f,
                    num_client_procs = num_client_procs,
                    num_clients_per_proc = num_clients_per_proc,
                    duration = datetime.timedelta(seconds=20),
                    timeout = datetime.timedelta(seconds=45),
                    client_lag = datetime.timedelta(seconds=5),
                    profiled = args.profile,
                    monitored = args.monitor,
                    prometheus_scrape_interval =
                        datetime.timedelta(milliseconds=200),
                    leader_options = LeaderOptions(),
                    leader_log_level = args.log_level,
                    dep_service_node_options = DepServiceNodeOptions(),
                    dep_service_node_log_level = args.log_level,
                    acceptor_options = AcceptorOptions(),
                    acceptor_log_level = args.log_level,
                    client_options = ClientOptions(),
                    client_log_level = args.log_level,
                    client_num_keys = 1000,
                )
                for f in [1, 2]
                for (num_client_procs, num_clients_per_proc) in
                    [(1, 1)] +
                    [(i, 10) for i in range(1, 5) if f == 1] +
                    [(i, 10) for i in range(1, 8) if f == 2]
            ] * 3

        def summary(self, input: Input, output: Output) -> str:
            return str({
                'f': input.f,
                'num_client_procs': input.num_client_procs,
                'num_clients_per_proc': input.num_clients_per_proc,
                'output.throughput_1s.p90': f'{output.throughput_1s.p90:.6}'
            })

    suite = ScaleUnanimousBPaxosSuite()
    with benchmark.SuiteDirectory(args.suite_directory,
                                  'unanimousbpaxos_scale') as dir:
        suite.run_suite(dir)


if __name__ == '__main__':
    _main(get_parser().parse_args())
