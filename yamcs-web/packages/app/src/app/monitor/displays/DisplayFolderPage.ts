import { SelectionModel } from '@angular/cdk/collections';
import { ChangeDetectionStrategy, Component, ElementRef, OnDestroy, ViewChild } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { MatTableDataSource } from '@angular/material/table';
import { Title } from '@angular/platform-browser';
import { ActivatedRoute, NavigationEnd, Router } from '@angular/router';
import { Instance, ListObjectsOptions, ListObjectsResponse, StorageClient } from '@yamcs/client';
import { BehaviorSubject, Subscription } from 'rxjs';
import { filter } from 'rxjs/operators';
import { AuthService } from '../../core/services/AuthService';
import { YamcsService } from '../../core/services/YamcsService';
import * as dnd from '../../shared/dnd';
import { CreateDisplayDialog } from './CreateDisplayDialog';
import { RenameDisplayDialog } from './RenameDisplayDialog';
import { UploadFilesDialog } from './UploadFilesDialog';

@Component({
  templateUrl: './DisplayFolderPage.html',
  styleUrls: ['./DisplayFolderPage.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DisplayFolderPage implements OnDestroy {

  @ViewChild('droparea', { static: true })
  dropArea: ElementRef;

  instance: Instance;

  breadcrumb$ = new BehaviorSubject<BreadCrumbItem[]>([]);
  dragActive$ = new BehaviorSubject<boolean>(false);

  displayedColumns = ['select', 'name', 'type', 'modified', 'actions'];
  dataSource = new MatTableDataSource<BrowseItem>([]);
  selection = new SelectionModel<BrowseItem>(true, []);

  private routerSubscription: Subscription;
  private storageClient: StorageClient;

  constructor(
    private dialog: MatDialog,
    yamcs: YamcsService,
    title: Title,
    private router: Router,
    private route: ActivatedRoute,
    private authService: AuthService,
  ) {
    title.setTitle('Displays');
    this.instance = yamcs.getInstance();
    this.storageClient = yamcs.createStorageClient();

    this.loadCurrentFolder();
    this.routerSubscription = router.events.pipe(
      filter(evt => evt instanceof NavigationEnd)
    ).subscribe(() => {
      this.loadCurrentFolder();
    });
  }

  private loadCurrentFolder() {
    const options: ListObjectsOptions = {
      delimiter: '/',
    };
    const routeSegments = this.route.snapshot.url;
    if (routeSegments.length) {
      options.prefix = routeSegments.map(s => s.path).join('/') + '/';
    }

    this.storageClient.listObjects('_global', 'displays', options).then(dir => {
      this.updateBrowsePath();
      this.changedir(dir);
    });
  }

  private changedir(dir: ListObjectsResponse) {
    this.selection.clear();
    const items: BrowseItem[] = [];
    for (const prefix of dir.prefixes || []) {
      items.push({
        folder: true,
        name: prefix,
      });
    }
    for (const object of dir.objects || []) {
      items.push({
        folder: false,
        name: object.name,
        modified: object.created,
        objectUrl: this.storageClient.getObjectURL('_global', 'displays', object.name),
      });
    }
    this.dataSource.data = items;
  }

  isAllSelected() {
    const numSelected = this.selection.selected.length;
    const numRows = this.dataSource.data.length;
    return numSelected === numRows && numRows > 0;
  }

  masterToggle() {
    this.isAllSelected() ?
        this.selection.clear() :
        this.dataSource.data.forEach(row => this.selection.select(row));
  }

  toggleOne(row: BrowseItem) {
    if (!this.selection.isSelected(row) || this.selection.selected.length > 1) {
      this.selection.clear();
    }
    this.selection.toggle(row);
  }

  createDisplay() {
    const dialogRef = this.dialog.open(CreateDisplayDialog, {
      width: '400px',
      data: {
        path: this.getCurrentPath(),
      }
    });
    dialogRef.afterClosed().subscribe(result => {
      if (result) {
        this.router.navigateByUrl(`/monitor/displays/files/${result}?instance=${this.instance.name}`);
      }
    });
  }

  uploadFiles() {
    const dialogRef = this.dialog.open(UploadFilesDialog, {
      width: '400px',
      data: {
        path: this.getCurrentPath(),
      }
    });
    dialogRef.afterClosed().subscribe(result => {
      if (result) {
        this.loadCurrentFolder();
      }
    });
  }

  private getCurrentPath() {
    let path = '';
    for (const segment of this.route.snapshot.url) {
      path += '/' + segment.path;
    }
    return path || '/';
  }

  deleteSelectedDisplays() {
    const deletableObjects: string[] = [];
    const findObjectPromises = [];
    for (const item of this.selection.selected) {
      if (item.folder) {
        findObjectPromises.push(this.storageClient.listObjects('_global', 'displays', {
          prefix: item.name,
        }).then(response => {
          const objects = response.objects || [];
          deletableObjects.push(...objects.map(o => o.name));
        }));
      } else {
        deletableObjects.push(item.name);
      }
    }

    Promise.all(findObjectPromises).then(() => {
      if (confirm(`You are about to delete ${deletableObjects.length} files. Are you sure you want to continue?`)) {
        const deletePromises = [];
        for (const object of deletableObjects) {
          deletePromises.push(this.storageClient.deleteObject('_global', 'displays', object));
        }

        Promise.all(deletePromises).then(() => {
          this.loadCurrentFolder();
        });
      }
    });
  }

  renameFile(item: BrowseItem) {
    const dialogRef = this.dialog.open(RenameDisplayDialog, {
      data: {
        name: item.name,
      },
      width: '400px',
    });
    dialogRef.afterClosed().subscribe(result => {
      if (result) {
        this.loadCurrentFolder();
      }
    });
  }

  deleteFile(item: BrowseItem) {
    if (confirm(`Are you sure you want to delete ${item.name}?`)) {
      this.storageClient.deleteObject('_global', 'displays', item.name).then(() => {
        this.loadCurrentFolder();
      });
    }
  }

  dragEnter(evt: DragEvent) {
    this.dragActive$.next(true);
    evt.preventDefault();
    evt.stopPropagation();
    return false;
  }

  dragOver(evt: DragEvent) { // This event must be prevented. Otherwise drop doesn't trigger.
    evt.preventDefault();
    evt.stopPropagation();
    return false;
  }

  dragLeave(evt: DragEvent) {
    this.dragActive$.next(false);
    evt.preventDefault();
    evt.stopPropagation();
    return false;
  }

  drop(evt: DragEvent) {
    const dataTransfer: any = evt.dataTransfer || {};
    if (dataTransfer) {
      let objectPrefix = this.getCurrentPath().substring(1);
      if (objectPrefix !== '') {
        objectPrefix += '/';
      }

      dnd.listDroppedFiles(dataTransfer).then(droppedFiles => {
        const uploadPromises: any[] = [];
        for (const droppedFile of droppedFiles) {
          const objectPath = objectPrefix + droppedFile._fullPath;
          const promise = this.storageClient.uploadObject('_global', 'displays', objectPath, droppedFile);
          uploadPromises.push(promise);
        }
        Promise.all(uploadPromises).finally(() => {
          this.loadCurrentFolder();
        });
      });
    }
    this.dragActive$.next(false);
    evt.preventDefault();
    evt.stopPropagation();
    return false;
  }

  mayManageDisplays() {
    const user = this.authService.getUser()!;
    return user.hasObjectPrivilege('ManageBucket', 'displays')
      || user.hasSystemPrivilege('ManageAnyBucket');
  }

  private updateBrowsePath() {
    const breadcrumb: BreadCrumbItem[] = [];
    let path = '';
    for (const segment of this.route.snapshot.url) {
      path += '/' + segment.path;
      breadcrumb.push({
        name: segment.path,
        route: '/monitor/displays/browse' + path,
      });
    }
    this.breadcrumb$.next(breadcrumb);
    return path || '/';
  }

  ngOnDestroy() {
    if (this.routerSubscription) {
      this.routerSubscription.unsubscribe();
    }
  }
}

export class BrowseItem {
  folder: boolean;
  name: string;
  modified?: string;
  objectUrl?: string;
}

export interface BreadCrumbItem {
  name: string;
  route: string;
}
