import 'reflect-metadata';
import dotenv from 'dotenv';
dotenv.config();

import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';
import { NestExpressApplication } from '@nestjs/platform-express';
import * as express from 'express';
import * as path from 'path';
import * as fs from 'fs';

async function bootstrap() {
  const app = await NestFactory.create<NestExpressApplication>(AppModule);

  app.enableCors();
  app.use(express.json());

  // Enable graceful shutdown hooks so NestJS calls OnApplicationShutdown
  app.enableShutdownHooks();

  const port = process.env.PORT || 8080;

  // Static files (frontend)
  const frontendDistPath = path.join(__dirname, '../../frontend/dist');
  if (fs.existsSync(frontendDistPath)) {
    // Serve static files
    app.use(express.static(frontendDistPath));

    // Redirect wildcards to index.html, skipping /api routes
    const expressApp = app.getHttpAdapter().getInstance();
    expressApp.get('*', (req: express.Request, res: express.Response, next: express.NextFunction) => {
      if (req.path.startsWith('/api')) {
        return next();
      }
      res.sendFile(path.join(frontendDistPath, 'index.html'));
    });
  } else {
    console.log(`Frontend build directory not found at ${frontendDistPath}. API server running as standalone.`);
  }

  await app.listen(port);
  console.log(`Server running on port ${port}`);
}

bootstrap().catch((err) => {
  console.error('Failed to start server:', err);
  process.exit(1);
});
