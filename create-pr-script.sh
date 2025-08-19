#!/bin/bash

# First-Class CLI Commands PR Creation Script
# This script helps create PRs across all three repositories for the first-class CLI commands feature
# 
# Usage: ./create-pr-script.sh
# 
# What this script does:
# 1. Checks the status of all three repositories
# 2. Provides commands to push branches and create PRs
# 3. Includes proper PR descriptions and linking

set -e

echo "🚀 First-Class CLI Commands - PR Creation Helper Script"
echo "======================================================="
echo ""

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Repository paths
NEXTFLOW_PLUGIN_GRADLE_PATH="/Users/edmundmiller/src/nextflow/nextflow-plugin-gradle"
NEXTFLOW_MAIN_PATH="/Users/edmundmiller/.worktrees/nextflow/cli-extension"
NF_WAVE_CLI_PATH="/Users/edmundmiller/src/nextflow/nf-wave-cli"

echo -e "${BLUE}📋 Repository Status Check${NC}"
echo "=========================="

# Function to check repository status
check_repo_status() {
    local repo_path=$1
    local repo_name=$2
    local expected_branch=$3
    
    echo ""
    echo -e "${YELLOW}Checking $repo_name...${NC}"
    cd "$repo_path"
    
    # Check current branch
    current_branch=$(git branch --show-current)
    echo "Current branch: $current_branch"
    
    # Check if on expected branch
    if [ "$current_branch" != "$expected_branch" ]; then
        echo -e "${RED}⚠️  WARNING: Expected branch '$expected_branch' but on '$current_branch'${NC}"
    fi
    
    # Check for uncommitted changes
    if ! git diff-index --quiet HEAD --; then
        echo -e "${RED}⚠️  WARNING: Uncommitted changes detected${NC}"
        git status --porcelain
    else
        echo -e "${GREEN}✅ Working directory clean${NC}"
    fi
    
    # Check commits ahead of origin
    local commits_ahead
    if git rev-parse --abbrev-ref @{u} >/dev/null 2>&1; then
        commits_ahead=$(git rev-list --count HEAD..@{u} 2>/dev/null || echo "0")
        local commits_behind=$(git rev-list --count @{u}..HEAD 2>/dev/null || echo "0")
        echo "Commits ahead of origin: $commits_behind"
        echo "Commits behind origin: $commits_ahead"
    else
        echo "No upstream branch configured"
    fi
    
    # Show recent commits
    echo "Recent commits:"
    git log --oneline -3
}

# Check each repository
check_repo_status "$NEXTFLOW_PLUGIN_GRADLE_PATH" "nextflow-plugin-gradle" "master"
check_repo_status "$NEXTFLOW_MAIN_PATH" "nextflow (main)" "cli-extension" 
check_repo_status "$NF_WAVE_CLI_PATH" "nf-wave-cli" "master"

echo ""
echo -e "${BLUE}🔍 Pre-PR Checklist${NC}"
echo "==================="
echo ""
echo "Before creating PRs, ensure:"
echo "✓ All commits are properly authored and have good commit messages"
echo "✓ All tests pass (run './gradlew test' in each repo)"
echo "✓ Code follows repository conventions"
echo "✓ No sensitive information is committed"
echo "✓ All changes are documented appropriately"
echo ""

echo -e "${BLUE}📤 Push Commands${NC}"
echo "================="
echo ""
echo "Execute these commands to push your branches:"
echo ""

echo -e "${YELLOW}1. Push nextflow-plugin-gradle changes:${NC}"
echo "cd $NEXTFLOW_PLUGIN_GRADLE_PATH"
echo "git push origin master"
echo ""

echo -e "${YELLOW}2. Push nextflow main changes:${NC}"
echo "cd $NEXTFLOW_MAIN_PATH"  
echo "git push origin cli-extension"
echo ""

echo -e "${YELLOW}3. Push nf-wave-cli changes:${NC}"
echo "cd $NF_WAVE_CLI_PATH"
echo "git push origin master"
echo ""

echo -e "${BLUE}🔗 PR Creation Commands${NC}"
echo "======================="
echo ""
echo "Execute these commands to create PRs (after pushing):"
echo ""

echo -e "${YELLOW}1. Create nextflow-plugin-gradle PR:${NC}"
cat << 'EOF'
cd /Users/edmundmiller/src/nextflow/nextflow-plugin-gradle
gh pr create --title "feat: Add extension point interfaces for standalone plugin development" --body "$(cat <<'PREOF'
## Summary

This PR adds extension point interfaces to enable standalone Nextflow plugin development. These interfaces provide compile-time access to Nextflow extension points without requiring the full Nextflow runtime as a dependency.

### Changes

- ✅ Add `PluginExtensionPoint` base interface for all plugin extensions
- ✅ Add `Factory` annotation for channel factory extensions  
- ✅ Add `Function` annotation for custom function extensions
- ✅ Add `Operator` annotation for channel operator extensions
- ✅ Add `CommandExtensionPoint` interface for first-class CLI commands
- ✅ Bump version to 1.0.0-beta.7

### Benefits

- **Standalone Development**: Plugins can be developed without full Nextflow runtime dependency
- **First-Class Commands**: Enables plugins to register commands that appear directly in main Nextflow CLI
- **Clean Architecture**: Separates compile-time interfaces from runtime implementation
- **Extensibility**: Foundation for future plugin system enhancements

### Related PRs

- Main Nextflow implementation: [Link to main PR]
- Wave CLI example implementation: [Link to nf-wave-cli PR]

### Testing

- ✅ All existing tests pass
- ✅ New interfaces compile correctly
- ✅ Version bump applied successfully

🤖 Generated with [Claude Code](https://claude.ai/code)
PREOF
)"
EOF
echo ""

echo -e "${YELLOW}2. Create main Nextflow PR:${NC}"
cat << 'EOF'
cd /Users/edmundmiller/.worktrees/nextflow/cli-extension
gh pr create --title "feat: Implement first-class CLI commands for plugins" --base master --head cli-extension --body "$(cat <<'PREOF'
## Summary

This PR implements a comprehensive system for Nextflow plugins to register first-class CLI commands that appear directly in the main Nextflow CLI, eliminating the need for verbose `nextflow plugin <plugin>:<command>` syntax.

### 🎯 Problem Solved

**Before**: `nextflow plugin nf-wave:command -- --help`  
**After**: `nextflow wave --help`

Users can now execute plugin commands as if they were built-in Nextflow commands.

### 🔧 Implementation

#### Core Components

- **CommandExtensionPoint Interface**: Allows plugins to register CLI commands
- **PluginCommandDiscovery**: Automatically discovers and registers plugin commands  
- **CLI Integration**: Plugin commands appear in main help output with descriptions
- **Priority System**: Handles conflicts when multiple plugins provide same command name

#### Architecture Changes

```
Main Nextflow CLI
├── Built-in Commands (run, clean, etc.)
└── Plugin Commands (discovered dynamically)
    └── wave (from nf-wave-cli plugin)
```

### 📋 Changes

#### New Files
- ✅ `CommandExtensionPoint.groovy` - Core interface for plugin command registration
- ✅ `PluginCommandBase.groovy` - Abstract base class extending CmdBase  
- ✅ `PluginCommandDiscovery.groovy` - Command discovery and registration system

#### Modified Files
- ✅ `Launcher.groovy` - Integrated plugin commands into main CLI
- ✅ Extension point cleanup - Moved interfaces to gradle plugin
- ✅ Wave plugin refactor - Removed CLI to avoid conflicts
- ✅ Build configuration - Updated for new architecture

#### Removed Files
- ✅ Wave CLI classes - Moved to separate nf-wave-cli plugin
- ✅ Duplicate extension points - Now in gradle plugin

### 🧪 Testing

- ✅ All existing functionality preserved
- ✅ Plugin commands appear in help output
- ✅ Commands execute with full functionality  
- ✅ Priority-based conflict resolution works
- ✅ Backward compatibility maintained

### 🔗 Related PRs

- Extension point interfaces: [Link to gradle plugin PR]
- Wave CLI implementation: [Link to nf-wave-cli PR]

### 📖 Usage Example

```bash
# Traditional verbose syntax
nextflow plugin nf-wave:command -- --help

# New first-class command syntax  
nextflow wave --help
```

### 🎉 Benefits

- **Better UX**: Clean, intuitive command syntax
- **Help Integration**: Commands appear in main help with descriptions
- **Extensible**: Easy for other plugins to add first-class commands
- **Maintainable**: Clear separation between core and plugin functionality

🤖 Generated with [Claude Code](https://claude.ai/code)
PREOF
)"
EOF
echo ""

echo -e "${YELLOW}3. Create nf-wave-cli PR:${NC}"
cat << 'EOF'
cd /Users/edmundmiller/src/nextflow/nf-wave-cli
gh pr create --title "feat: Implement Wave CLI as Nextflow first-class command" --body "$(cat <<'PREOF'
## Summary

This PR transforms the Wave CLI into a first-class Nextflow command that appears directly in the main Nextflow CLI help and can be executed as `nextflow wave` instead of the verbose plugin syntax.

### 🎯 User Experience Transformation

**Before**: `nextflow plugin nf-wave:command -- --help`  
**After**: `nextflow wave --help`

The Wave command now appears in the main Nextflow help output:
```
Commands:
  clean         Clean up project cache and work directories
  clone         Clone a project into a folder
  ...
  wave          Wave container provisioning and management
```

### 🔧 Implementation

#### New Components

- **WaveCommand**: Extends `CmdBase` for full Nextflow CLI integration
- **WaveCommandExtensionPoint**: Registers Wave as a first-class command
- **Plugin Configuration**: Standalone development and distribution setup

#### Key Features

- ✅ **Full Integration**: Seamless integration with existing Wave CLI functionality
- ✅ **Complete Features**: All Wave CLI options, examples, and help text preserved
- ✅ **Help Support**: Proper help integration with Nextflow CLI system
- ✅ **Standalone**: Independent development and distribution from main Nextflow

### 📋 Changes

#### New Files
- ✅ `WaveCommand.groovy` - Main command implementation extending CmdBase
- ✅ `WaveCommandExtensionPoint.groovy` - Extension point for command registration

#### Modified Files  
- ✅ `build.gradle` - Plugin configuration and dependencies
- ✅ `WavePlugin.groovy` - Updated plugin main class
- ✅ `Makefile` - Build and installation automation
- ✅ `extensions.idx` - Extension registration
- ✅ `settings.gradle` - Plugin development configuration

### 🧪 Functionality

All Wave CLI features work exactly as before:

```bash
# Container provisioning
nextflow wave -i alpine --layer layer-dir/

# Dockerfile builds  
nextflow wave -f Dockerfile --context context-dir/

# Conda packages
nextflow wave --conda-package samtools=1.17 --conda-package bamtools=2.5.2

# Singularity support
nextflow wave --conda-package fastp --singularity --freeze

# Security scanning
nextflow wave -i ubuntu --scan-mode required --await
```

### 🏗️ Architecture

This plugin demonstrates the new first-class command system:

1. **Extension Point**: Implements `CommandExtensionPoint` interface
2. **Command Registration**: Automatically discovered by Nextflow at startup
3. **CLI Integration**: Appears in main help and executes as top-level command
4. **Full Features**: Complete access to all Wave CLI functionality

### 🔗 Related PRs

- Extension point system: [Link to main Nextflow PR]
- Gradle plugin interfaces: [Link to gradle plugin PR]

### 🎉 Benefits

- **Intuitive UX**: Clean `nextflow wave` syntax
- **Discovery**: Appears in main Nextflow help  
- **Standalone**: Independent development lifecycle
- **Template**: Example for other plugins to follow

This implementation serves as the reference example for how plugins can provide first-class CLI commands using the new extension point system.

🤖 Generated with [Claude Code](https://claude.ai/code)
PREOF
)"
EOF
echo ""

echo -e "${BLUE}📋 Post-PR Creation Steps${NC}"
echo "========================="
echo ""
echo "After creating all PRs:"
echo ""
echo "1. 🔗 Update PR descriptions with links to related PRs"
echo "2. 🏷️  Add appropriate labels to each PR"
echo "3. 👥 Request reviews from relevant team members"
echo "4. 🧪 Ensure all CI checks pass"
echo "5. 📖 Update documentation if needed"
echo "6. 🎉 Coordinate merge order (gradle plugin → main → wave-cli)"
echo ""

echo -e "${BLUE}📝 Notes${NC}"
echo "========="
echo ""
echo "• Merge order is important: gradle plugin first, then main Nextflow, then nf-wave-cli"
echo "• Update PR descriptions with actual links once all PRs are created"  
echo "• Consider creating a demo video or GIF showing the new functionality"
echo "• May want to create draft PRs first for initial review"
echo ""

echo -e "${GREEN}✅ Script complete! Ready for PR creation on Monday.${NC}"
echo ""
echo "Save this script output for reference when creating the PRs."