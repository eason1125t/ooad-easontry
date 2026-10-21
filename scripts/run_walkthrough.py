"""Compatibility entry point for the current cross-system rehearsal.

Historical single-system TXT reports are preserved. The V4 rehearsal lives in
run_integration_walkthrough.py and creates a separate timestamped report.
"""
from run_integration_walkthrough import main

if __name__ == '__main__':
    main()
