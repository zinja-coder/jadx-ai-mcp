# Contributing to jadx-ai-mcp and jadx-mcp-server

Thank you for considering contributing to the project! We welcome contributions that improve functionality, performance, documentation, or overall code quality.

## How to Contribute

1. **Fork the repository**
   - Click "Fork" at the top-right of the repository page.
   
2. **Clone your fork**
   ```bash
   git clone https://github.com/your-username/jadx-ai-mcp.git
   # or
   git clone https://github.com/your-username/jadx-mcp-server.git

   # Create a new branch
   git checkout -b your-feature-branch

   # Add jadx jar file as dependency in your local maven repository
   mvn install:install-file -Dfile=path/to/jadx-<version>-all.jar \
                         -DgroupId=io.github.skylot \
                         -DartifactId=jadx-all \
                         -Dversion=<version> \
                         -Dpackaging=jar

   # For example
   mvn install:install-file -Dfile=lib/jadx-1.5.1-all.jar \
                         -DgroupId=io.github.skylot \
                         -DartifactId=jadx-all \
                         -Dversion=1.5.1 \
                         -Dpackaging=jar


   ```

3. **Make your changes**

   - Follow the coding style used in the project.

   - Keep commits atomic and meaningful.

   - Add/update tests if necessary.

   - Update documentation if behavior changes.

4. **Test your changes**

   - Make sure everything builds and works properly.

   - Run existing tests and new ones you add.

5. **Submit a Pull Request**

   - Push your branch to your fork.

   - Open a pull request against the main branch.

   - Fill out the Pull Request Template.

6. **Code Style and Practices**

   - Write clear, concise commit messages.

   - Keep pull requests focused and small.

   - Document any new features or major changes.

   - Maintain backward compatibility where possible.

### Development Requirements

   - Java 11+ for jadx-ai-mcp

   - Python 3.10+ for jadx-mcp-server

   - Familiarity with MCP, jadx, and Android reverse engineering concepts is a plus.

### Communication

If you plan to contribute a larger feature or change, please open an issue first to discuss it.

**Thanks for helping make this project better! You are awesome and thanks in advance.**
