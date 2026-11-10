// 精简版 librclone：把 rclone 编成进程内调用的 C 共享库。
//
// 只注册 WebDAV / SFTP / SMB 三个后端，以及本项目需要的 RC 端点：
//   - cmd/serve + cmd/serve/restic → serve/start type=restic、serve/stop、serve/list
//   - fs/operations                 → operations/list、operations/mkdir
//
// 官方 librclone/librclone.go 默认 import backend/all + mount/cmount + sync，
// 这里全部裁掉，产物从 29MB 降到约 19MB。新增后端 / RC 端点时改这里的 import。
package main

/*
#include <stdlib.h>

struct RcloneRPCResult {
	char*	Output;
	int	Status;
};
*/
import "C"

import (
	"unsafe"

	"github.com/rclone/rclone/fs/config/obscure"
	"github.com/rclone/rclone/librclone/librclone"

	_ "github.com/rclone/rclone/backend/local"
	_ "github.com/rclone/rclone/backend/sftp"
	_ "github.com/rclone/rclone/backend/smb"
	_ "github.com/rclone/rclone/backend/webdav"
	_ "github.com/rclone/rclone/cmd/serve"
	_ "github.com/rclone/rclone/cmd/serve/restic"
	_ "github.com/rclone/rclone/fs/operations"
)

//export RcloneInitialize
func RcloneInitialize() {
	librclone.Initialize()
}

//export RcloneFinalize
func RcloneFinalize() {
	librclone.Finalize()
}

//export RcloneRPC
func RcloneRPC(method *C.char, input *C.char) (result C.struct_RcloneRPCResult) {
	output, status := librclone.RPC(C.GoString(method), C.GoString(input))
	result.Output = C.CString(output)
	result.Status = C.int(status)
	return result
}

//export RcloneFreeString
func RcloneFreeString(str *C.char) {
	C.free(unsafe.Pointer(str))
}

// RcloneObscure 把明文服务密码转成 rclone 配置里要求的混淆形式。
//
// inline fs 配置（以及 connection string）都会对 pass 做 Reveal，
// 直接写明分会报 "couldn't decrypt password"。服务端 HTTP basic auth
// 的 user/pass 不走 Reveal，不需要这个转换。
//
//export RcloneObscure
func RcloneObscure(plain *C.char) *C.char {
	return C.CString(obscure.MustObscure(C.GoString(plain)))
}

func main() {}
