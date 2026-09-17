import type { LinkDiagnostic } from "../components/LinkDiagnosticsPanel";
import type { AppServiceStatus } from "../AppContext";

export type ServiceStatus = AppServiceStatus & {
  port?: number;
  heartbeatPhase?: string;
  heartbeatHttpReady?: boolean;
  heartbeatImReady?: boolean;
  heartbeatReady?: boolean;
  lastLinkDiagnostic?: LinkDiagnostic | null;
};

export const externalRunningStatus = (pid: number | null = null): ServiceStatus => ({
  running: true,
  pid,
  pidFile: "",
  managedBy: "external",
  isManagedChild: false,
});

export const stoppedStatus = (): ServiceStatus => ({
  running: false,
  pid: null,
  pidFile: "",
  managedBy: "unknown",
  isManagedChild: false,
});
