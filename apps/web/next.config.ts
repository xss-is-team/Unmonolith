import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  allowedDevOrigins: ["127.0.0.1"],
  skipTrailingSlashRedirect: true,
  async rewrites() {
    const apiOrigin = (process.env.BLUEPRINTS_API_ORIGIN ?? "http://127.0.0.1:3000").replace(/\/$/, "");
    return [
      { source: "/api/v1/:path*", destination: `${apiOrigin}/api/v1/:path*` },
      { source: "/swagger-ui", destination: `${apiOrigin}/swagger-ui/` },
      { source: "/swagger-ui/", destination: `${apiOrigin}/swagger-ui/` },
      { source: "/swagger-ui/:path*", destination: `${apiOrigin}/swagger-ui/:path*` },
    ];
  },
};

export default nextConfig;
