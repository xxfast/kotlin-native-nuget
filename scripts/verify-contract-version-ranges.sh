#!/usr/bin/env bash
set -euo pipefail

# Actual NuGet restore proves publishers with differing compatible contract floors can coexist.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$scratch/feed" "$scratch/First" "$scratch/Second" "$scratch/App"
cat > "$scratch/NuGet.Config" <<EOF
<configuration><packageSources><clear />
  <add key="scratch" value="feed" />
  <add key="nuget.org" value="https://api.nuget.org/v3/index.json" />
</packageSources></configuration>
EOF

dotnet pack "$ROOT/Kotlin.Native.Interop" -c Release -o "$scratch/feed" -p:PackageVersion=1.0.0
dotnet pack "$ROOT/Kotlin.Native.Interop" -c Release -o "$scratch/feed" -p:PackageVersion=1.0.1

for publisher in First Second; do
  floor=1.0.0
  if [ "$publisher" = Second ]; then floor=1.0.1; fi
  cat > "$scratch/$publisher/$publisher.csproj" <<EOF
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup><TargetFramework>net8.0</TargetFramework><PackageId>ContractFloor.$publisher</PackageId></PropertyGroup>
  <ItemGroup><PackageReference Include="Kotlin.Native.Interop" Version="[$floor,2.0.0)" /></ItemGroup>
</Project>
EOF
  dotnet restore "$scratch/$publisher" --configfile "$scratch/NuGet.Config" \
    --packages "$scratch/packages"
  dotnet pack "$scratch/$publisher" --no-restore -o "$scratch/feed"
done

cat > "$scratch/App/App.csproj" <<EOF
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup>
  <ItemGroup>
    <PackageReference Include="ContractFloor.First" Version="1.0.0" />
    <PackageReference Include="ContractFloor.Second" Version="1.0.0" />
  </ItemGroup>
</Project>
EOF
dotnet restore "$scratch/App" --configfile "$scratch/NuGet.Config" --packages "$scratch/packages"
grep -q '"Kotlin.Native.Interop/1.0.1"' "$scratch/App/obj/project.assets.json"
if grep -q '"Kotlin.Native.Interop/1.0.0"' "$scratch/App/obj/project.assets.json"; then
  echo 'FAIL: restore retained the older contract alongside the common resolution' >&2
  exit 1
fi
echo 'OK: [1.0.0,2.0.0) and [1.0.1,2.0.0) resolve one compatible 1.0.1 contract'
