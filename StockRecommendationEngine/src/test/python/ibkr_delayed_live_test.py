"""Read-only, opt-in AAPL delayed quote test using the official TWS Python API.

Requires the official IBKR Python client on PYTHONPATH (and its dependencies).
Log into paper TWS / IB Gateway and enable its socket API, preferably read-only.
Run: python3 src/test/python/ibkr_delayed_live_test.py --port 7497
Use --port 4002 for the usual paper IB Gateway port. Client Portal port 5001
cannot handle this protocol. This is a standalone diagnostic; the application uses the Java TWS socket adapter.

Defaults to type 4 (delayed frozen); --market-data-type 3 requests delayed streaming.
Success requires delayed price ticks and the requested callback type.
IBKR can override the request with live data when entitled; that is reported
as a failure to verify delayed delivery, not mislabeled as delayed data.
Reference: https://www.interactivebrokers.com/docs/tws-api/doc/market-data-delayed/introduction
"""

import argparse
import math
import threading


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=7497)
    parser.add_argument('--client-id', type=int, default=93)
    parser.add_argument('--market-data-type', type=int, choices=(3, 4), default=4)
    parser.add_argument('--timeout', type=float, default=30)
    args = parser.parse_args()
    if not 0 < args.timeout <= 120 or not 0 < args.port < 65536:
        parser.error('Use a valid port and a timeout between 0 and 120 seconds')

    from ibapi.client import EClient
    from ibapi.wrapper import EWrapper
    from ibapi.contract import Contract

    class Probe(EWrapper, EClient):
        def __init__(self):
            EClient.__init__(self, self)
            self.ready = threading.Event()
            self.delayed = threading.Event()
            self.data_type = None
            self.prices = {}
            self.codes = set()

        def nextValidId(self, orderId):
            self.ready.set()

        def marketDataType(self, reqId, marketDataType):
            if reqId == 1:
                self.data_type = marketDataType
                self.check_result()

        def tickPrice(self, reqId, tickType, price, attrib):
            if reqId == 1 and tickType in (66, 67, 68) and math.isfinite(price) and price > 0:
                self.prices[tickType] = price
                self.check_result()

        def check_result(self):
            if self.data_type == args.market_data_type and self.prices:
                self.delayed.set()

        def error(self, reqId, *details):
            # SDK versions differ: newer callbacks insert errorTime before code.
            code = details[1] if len(details) >= 3 and isinstance(details[1], int) else details[0]
            self.codes.add(code)
            print(f'IBKR message code: {code}', flush=True)

    app = Probe()
    worker = None
    requested = False
    try:
        app.connect('127.0.0.1', args.port, clientId=args.client_id)
        if not app.isConnected():
            raise RuntimeError('Socket API unavailable; start paper TWS/IB Gateway and enable API connections')
        worker = threading.Thread(target=app.run, daemon=True)
        worker.start()
        if not app.ready.wait(args.timeout):
            raise RuntimeError('Socket API handshake timed out')
        contract = Contract()
        contract.symbol = 'AAPL'
        contract.secType = 'STK'
        contract.exchange = 'SMART'
        contract.primaryExchange = 'NASDAQ'
        contract.currency = 'USD'
        app.reqMarketDataType(args.market_data_type)
        print(f'Requested market data type {args.market_data_type} for AAPL', flush=True)
        app.reqMktData(1, contract, '', False, False, [])
        requested = True
        if not app.delayed.wait(args.timeout):
            raise RuntimeError(f'No confirmed delayed quote: returned type={app.data_type}, '
                               f'delayed price ticks={sorted(app.prices)}, codes={sorted(app.codes)}')
        print(f'PASS: type {args.market_data_type} confirmed with positive delayed bid/ask/last data')
    finally:
        if requested and app.isConnected():
            app.cancelMktData(1)
        app.disconnect()
        if worker:
            worker.join(timeout=2)


if __name__ == '__main__':
    main()
